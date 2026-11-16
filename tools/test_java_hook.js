// Hook Resources.getSystem().getDisplayMetrics().
console.log("display-metrics hook start");

function printStack(tag) {
  try {
    var th = Java._new("java.lang.Throwable", "()V", []);
    var text = Java._invoke(
      "android.util.Log",
      "getStackTraceString",
      "(Ljava/lang/Throwable;)Ljava/lang/String;",
      null,
      [th],
      1
    );
    var lines = String(text).split("\n");
    var i;
    console.log(tag + " stack");
    for (i = 0; i < lines.length && i < 40; i++) {
      if (lines[i]) {
        console.log(lines[i]);
      }
    }
  } catch (e) {
    console.log(tag + " stack err " + e);
  }
}

function sameObject(a, b) {
  if (a == null || b == null) {
    return false;
  }
  return Java._invoke(
    "java.lang.Object",
    "equals",
    "(Ljava/lang/Object;)Z",
    a,
    [b],
    0
  ) === true;
}

function systemResources() {
  return Java._invoke(
    "android.content.res.Resources",
    "getSystem",
    "()Landroid/content/res/Resources;",
    null,
    [],
    1
  );
}

function superclass(cls) {
  return Java._invoke("java.lang.Class", "getSuperclass", "()Ljava/lang/Class;", cls, [], 0);
}

function findField(obj, name) {
  var cls = Java._invoke("java.lang.Object", "getClass", "()Ljava/lang/Class;", obj, [], 0);
  while (cls != null) {
    try {
      var f = Java._invoke(
        "java.lang.Class",
        "getDeclaredField",
        "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
        cls,
        [name],
        0
      );
      Java._invoke("java.lang.reflect.AccessibleObject", "setAccessible", "(Z)V", f, [true], 0);
      return f;
    } catch (e) {
      cls = superclass(cls);
    }
  }
  return null;
}

function readField(obj, name) {
  var f = findField(obj, name);
  if (f == null) {
    return null;
  }
  return Java._invoke(
    "java.lang.reflect.Field",
    "get",
    "(Ljava/lang/Object;)Ljava/lang/Object;",
    f,
    [obj],
    0
  );
}

function fieldText(obj, name) {
  try {
    var v = readField(obj, name);
    if (v == null) {
      return name + "=null";
    }
    return name + "=" + Java.str(v);
  } catch (e) {
    return name + "=err " + e;
  }
}

function metricsObject(resources) {
  try {
    var impl = readField(resources, "mResourcesImpl");
    if (impl != null) {
      var nested = readField(impl, "mMetrics");
      if (nested != null) {
        return nested;
      }
    }
  } catch (e) {}
  try {
    return readField(resources, "mMetrics");
  } catch (e2) {
    return null;
  }
}

function dumpMetrics(tag, dm) {
  if (dm == null) {
    console.log(tag + " DisplayMetrics=null");
    return;
  }
  console.log(
    tag +
      " " +
      fieldText(dm, "widthPixels") +
      " " +
      fieldText(dm, "heightPixels") +
      " " +
      fieldText(dm, "density") +
      " " +
      fieldText(dm, "densityDpi")
  );
}

var systemCalls = [];

Java.hook(
  "android.content.res.Resources",
  "getDisplayMetrics",
  "()Landroid/util/DisplayMetrics;",
  {
    onEnter: function (args) {
      var fromSystem = false;
      try {
        fromSystem = sameObject(args[0], systemResources());
      } catch (e) {
        console.log("getDisplayMetrics enter err " + e);
      }
      systemCalls.push(fromSystem);
      console.log("getDisplayMetrics enter system=" + fromSystem);
      dumpMetrics(fromSystem ? "system" : "app", metricsObject(args[0]));
    },
    onLeave: function (dm) {
      var fromSystem = systemCalls.length ? systemCalls.pop() : false;
      dumpMetrics((fromSystem ? "system" : "app") + " return", dm);
    }
  }
);

console.log("hooked Resources.getDisplayMetrics");
// Java._invoke of a hooked method calls the backup and skips the hook.
// Method.invoke goes through the hooked entry.
var system = systemResources();
var cls = Java._invoke("java.lang.Object", "getClass", "()Ljava/lang/Class;", system, [], 0);
var method = Java._invoke(
  "java.lang.Class",
  "getMethod",
  "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
  cls,
  ["getDisplayMetrics", null],
  0
);
Java._invoke(
  "java.lang.reflect.Method",
  "invoke",
  "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
  method,
  [system, null],
  0
);
console.log("fired getSystem().getDisplayMetrics via Method.invoke");
