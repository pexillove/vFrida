package com.android.bb.hook;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import black.android.os.BRServiceManager;


public abstract class BinderInvocationStub extends ClassInvocationStub implements IBinder {
    private IBinder mBaseBinder;
    /** IBinder proxy placed in ServiceManager.sCache. Its class name is $ProxyN. */
    private IBinder mServiceBinder;

    public BinderInvocationStub(IBinder baseBinder) {
        mBaseBinder = baseBinder;
    }

    @Override
    protected void onBindMethod() {
    }

    @Nullable
    @Override
    public String getInterfaceDescriptor() throws RemoteException {
        return mBaseBinder.getInterfaceDescriptor();
    }

    @Override
    public boolean pingBinder() {
        return mBaseBinder.pingBinder();
    }

    @Override
    public boolean isBinderAlive() {
        return mBaseBinder.isBinderAlive();
    }

    @Nullable
    @Override
    public IInterface queryLocalInterface(@NonNull String descriptor) {
        return (IInterface) getProxyInvocation();
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @Nullable String[] args) throws RemoteException {
        mBaseBinder.dump(fd, args);
    }

    @Override
    public void dumpAsync(@NonNull FileDescriptor fd, @Nullable String[] args) throws RemoteException {
        mBaseBinder.dumpAsync(fd, args);
    }

    @Override
    public boolean transact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
        return mBaseBinder.transact(code, data, reply, flags);
    }

    @Override
    public void linkToDeath(@NonNull DeathRecipient recipient, int flags) throws RemoteException {
        mBaseBinder.linkToDeath(recipient, flags);
    }

    @Override
    public boolean unlinkToDeath(@NonNull DeathRecipient recipient, int flags) {
        return mBaseBinder.unlinkToDeath(recipient, flags);
    }


    protected void replaceSystemService(String name) {
        Map<String, IBinder> services = BRServiceManager.get().sCache();
        services.put(name, serviceBinder());
    }

    /**
     * A scanner that reads {@code ServiceManager.getService} accepts {@code BinderProxy}
     * or a class whose name ends in {@code $Proxy} plus digits. The stub's own class is
     * neither, so the cache holds a proxy of {@link IBinder} and forwards to the stub.
     */
    private IBinder serviceBinder() {
        if (mServiceBinder == null) {
            mServiceBinder = (IBinder) Proxy.newProxyInstance(
                    BinderInvocationStub.class.getClassLoader(),
                    new Class<?>[]{IBinder.class},
                    this::invokeBinder);
        }
        return mServiceBinder;
    }

    private Object invokeBinder(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            String name = method.getName();
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == (args != null && args.length > 0 ? args[0] : null);
            }
            if ("toString".equals(name)) {
                return proxy.getClass().getName() + "@"
                        + Integer.toHexString(System.identityHashCode(proxy));
            }
        }
        Method target = method;
        Object receiver = mBaseBinder;
        try {
            Method declared = getClass().getMethod(method.getName(), method.getParameterTypes());
            if (!declared.getDeclaringClass().isInterface()) {
                target = declared;
                receiver = this;
            }
        } catch (NoSuchMethodException ignored) {
        }
        try {
            return target.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                throw cause;
            }
            throw e;
        }
    }
}
