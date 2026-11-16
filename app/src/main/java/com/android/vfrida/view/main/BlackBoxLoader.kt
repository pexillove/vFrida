package com.android.vfrida.view.main

import android.content.Context
import android.util.Log
import com.android.bb.BbCore
import com.android.bb.BConfig
import com.android.vfrida.app.App
import com.android.vfrida.app.RockerManager
import com.android.vfrida.app.AppSharedPreferenceDelegate


class BlackBoxLoader {

    private var mDaemonEnable by AppSharedPreferenceDelegate(App.getContext(), true)
    private var mShowShortcutPermissionDialog by AppSharedPreferenceDelegate(App.getContext(), true)

    private var mDisableFlagSecure by AppSharedPreferenceDelegate(App.getContext(), false)

    private var mXhsFingerprintDump by AppSharedPreferenceDelegate(App.getContext(), false)

    private var mSeccompPathRedirect by AppSharedPreferenceDelegate(App.getContext(), false)

    fun disableFlagSecure(): Boolean {
        return try {
            mDisableFlagSecure
        } catch (e: Exception) {
            Log.e(TAG, "Error getting disableFlagSecure: ${e.message}")
            false
        }
    }

    fun invalidDisableFlagSecure(disable: Boolean) {
        try {
            this.mDisableFlagSecure = disable
            BConfig.get().put(BConfig.KEY_DISABLE_FLAG_SECURE, disable)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting disableFlagSecure: ${e.message}")
        }
    }

    fun daemonEnable(): Boolean {
        return try {
            mDaemonEnable
        } catch (e: Exception) {
            Log.e(TAG, "Error getting daemonEnable: ${e.message}")
            false
        }
    }

    fun invalidDaemonEnable(enable: Boolean) {
        try {
            this.mDaemonEnable = enable
            BConfig.get().put(BConfig.KEY_ENABLE_DAEMON, enable)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting daemonEnable: ${e.message}")
        }
    }

    fun showShortcutPermissionDialog(): Boolean {
        return try {
            mShowShortcutPermissionDialog
        } catch (e: Exception) {
            Log.e(TAG, "Error getting showShortcutPermissionDialog: ${e.message}")
            true
        }
    }

    fun invalidShortcutPermissionDialog(show: Boolean) {
        try {
            this.mShowShortcutPermissionDialog = show
        } catch (e: Exception) {
            Log.e(TAG, "Error setting showShortcutPermissionDialog: ${e.message}")
        }
    }

    fun xhsFingerprintDump(): Boolean {
        return try {
            mXhsFingerprintDump
        } catch (e: Exception) {
            Log.e(TAG, "Error getting xhsFingerprintDump: ${e.message}")
            false
        }
    }

    fun invalidXhsFingerprintDump(enable: Boolean) {
        try {
            this.mXhsFingerprintDump = enable
            BConfig.get().put(BConfig.KEY_XHS_FINGERPRINT_DUMP, enable)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting xhsFingerprintDump: ${e.message}")
        }
    }

    fun seccompPathRedirect(): Boolean {
        return try {
            mSeccompPathRedirect
        } catch (e: Exception) {
            Log.e(TAG, "Error getting seccompPathRedirect: ${e.message}")
            false
        }
    }

    fun invalidSeccompPathRedirect(enable: Boolean) {
        try {
            this.mSeccompPathRedirect = enable
            BConfig.get().put(BConfig.KEY_SECCOMP_PATH_REDIRECT, enable)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting seccompPathRedirect: ${e.message}")
        }
    }

    fun getBbCore(): BbCore {
        return try {
            BbCore.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting BbCore: ${e.message}")
            throw e
        }
    }

    fun attachBaseContext(context: Context) {
        try {
            val hostPkg = try {
                context.packageName
            } catch (e: Exception) {
                Log.e(TAG, "Error getting package name: ${e.message}")
                "unknown"
            }
            BConfig.get()
                    .put(BConfig.KEY_HOST_PACKAGE, hostPkg)
                    .put(BConfig.KEY_ENABLE_DAEMON, daemonEnable())
                    .put(BConfig.KEY_DISABLE_FLAG_SECURE, disableFlagSecure())
                    .put(BConfig.KEY_XHS_FINGERPRINT_DUMP, xhsFingerprintDump())
                    .put(BConfig.KEY_SECCOMP_PATH_REDIRECT, seccompPathRedirect())
                    .put(
                            BConfig.KEY_INSTALL_PACKAGE_HANDLER,
                            BConfig.InstallPackageHandler { file, _ ->
                                try {
                                    if (file == null) {
                                        Log.w(TAG, "requestInstallPackage: file is null")
                                        return@InstallPackageHandler false
                                    }
                                    context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                                    false
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error in requestInstallPackage: ${e.message}")
                                    false
                                }
                            },
                    )
                    .put(
                            BConfig.KEY_AFTER_APPLICATION_CREATE,
                            BConfig.AfterApplicationCreateHandler { _, _, application, userId ->
                                RockerManager.init(application, userId)
                            },
                    )
                    .put(
                            BConfig.KEY_STORAGE_PERMISSION_HANDLER,
                            BConfig.StoragePermissionHandler { packageName, userId ->
                                try {
                                    Log.w(TAG, "Storage permission needed for launching: $packageName")
                                    val intent = android.content.Intent(
                                            "com.android.vfrida.REQUEST_STORAGE_PERMISSION",
                                    )
                                    intent.putExtra("package_name", packageName)
                                    intent.putExtra("user_id", userId)
                                    intent.setPackage(App.getContext().packageName)
                                    App.getContext().sendBroadcast(intent)
                                    false
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error in storage permission handler: ${e.message}")
                                    false
                                }
                            },
                    )
            BbCore.get().doAttachBaseContext(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error in attachBaseContext: ${e.message}")
        }
    }

    fun doOnCreate(context: Context) {
        try {
            BbCore.get().doCreate()

            
            try {
                BbCore.get().addServiceAvailableCallback {
                    Log.d(TAG, "Services became available, triggering app list refresh")
                    
                    
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error registering service available callback: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in doOnCreate: ${e.message}")
        }
    }

    companion object {
        val TAG: String = BlackBoxLoader::class.java.simpleName
    }
}
