// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.rt.debugger.agent;

import org.jetbrains.org.objectweb.asm.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.jar.JarFile;

/**
 * @author egor
 */
@SuppressWarnings({"UseOfSystemOutOrSystemErr", "CallToPrintStackTrace"})
public class CaptureAgent {
  private static Instrumentation ourInstrumentation;
  private static boolean DEBUG = false;

  static final KeyProvider THIS_KEY_PROVIDER = new ParamKeyProvider(0);

  private static Map<String, List<CapturePoint>> myCapturePoints = new HashMap<String, List<CapturePoint>>();
  private static Map<String, List<InsertPoint>> myInsertPoints = new HashMap<String, List<InsertPoint>>();

  static {
    addCapturePoint("javax/swing/SwingUtilities", "invokeLater", new ParamKeyProvider(0));
    addInsertPoint("java/awt/event/InvocationEvent", "dispatch",
                   new FieldKeyProvider("java/awt/event/InvocationEvent", "runnable", "Ljava/lang/Runnable;"));

    addCapturePoint("java/lang/Thread", "start", THIS_KEY_PROVIDER);
    addInsertPoint("java/lang/Thread", "run", THIS_KEY_PROVIDER);

    addCapturePoint("java/util/concurrent/ExecutorService", "submit", new ParamKeyProvider(1));
    addInsertPoint("java/util/concurrent/Executors$RunnableAdapter", "call",
                   new FieldKeyProvider("java/util/concurrent/Executors$RunnableAdapter", "task", "Ljava/lang/Runnable;"));

    addCapturePoint("java/util/concurrent/ThreadPoolExecutor", "execute", new ParamKeyProvider(1));
    addInsertPoint("java/util/concurrent/FutureTask", "run", THIS_KEY_PROVIDER);

    addCapturePoint("java/util/concurrent/CompletableFuture", "supplyAsync", new ParamKeyProvider(0));
    addInsertPoint("java/util/concurrent/CompletableFuture$AsyncSupply", "run",
                   new FieldKeyProvider("java/util/concurrent/CompletableFuture$AsyncSupply", "fn", "Ljava/util/function/Supplier;"));

    addCapturePoint("java/util/concurrent/CompletableFuture", "runAsync", new ParamKeyProvider(0));
    addInsertPoint("java/util/concurrent/CompletableFuture$AsyncRun", "run",
                   new FieldKeyProvider("java/util/concurrent/CompletableFuture$AsyncRun", "fn", "Ljava/lang/Runnable;"));

    addCapturePoint("java/util/concurrent/CompletableFuture", "thenAcceptAsync", new ParamKeyProvider(1));
    addInsertPoint("java/util/concurrent/CompletableFuture", "uniAccept", new ParamKeyProvider(2));
    //addInsertPoint("java/util/concurrent/CompletableFuture$UniAccept", "tryFire",
    //               new FieldKeyProvider("java/util/concurrent/CompletableFuture$UniAccept", "fn", "Ljava/util/function/Consumer;"));

    addCapturePoint("java/util/concurrent/CompletableFuture", "thenRunAsync", new ParamKeyProvider(1));
    addInsertPoint("java/util/concurrent/CompletableFuture", "uniRun", new ParamKeyProvider(2));
  }

  public static void premain(String args, Instrumentation instrumentation) throws IOException {
    ourInstrumentation = instrumentation;

    String asmPath = null;
    if (args != null) {
      String[] split = args.split(";");
      for (String s : split) {
        if ("debug".equals(s)) {
          DEBUG = true;
          CaptureStorage.setDebug(true);
        }
        else {
          asmPath = s;
        }
      }
    }

    if (asmPath == null) {
      System.out.println("Capture agent: asm path is not specified, exiting");
      return;
    }
    instrumentation.appendToSystemClassLoaderSearch(new JarFile(asmPath));

    instrumentation.addTransformer(new CaptureTransformer());
    for (Class aClass : instrumentation.getAllLoadedClasses()) {
      String name = aClass.getName().replaceAll("\\.", "/");
      if (myCapturePoints.containsKey(name) || myInsertPoints.containsKey(name)) {
        try {
          instrumentation.retransformClasses(aClass);
        }
        catch (UnmodifiableClassException e) {
          e.printStackTrace();
        }
      }
    }
    if (DEBUG) {
      System.out.println("Capture agent: ready");
    }
  }

  private static <T> List<T> getNotNull(List<T> list) {
    return list != null ? list : Collections.<T>emptyList();
  }

  private static class CaptureTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
      if (className != null) {
        List<CapturePoint> capturePoints = getNotNull(myCapturePoints.get(className));
        List<InsertPoint> insertPoints = getNotNull(myInsertPoints.get(className));
        if (!capturePoints.isEmpty() || !insertPoints.isEmpty()) {
          ClassReader reader = new ClassReader(classfileBuffer);
          ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

          try {
            reader.accept(new CaptureInstrumentor(Opcodes.ASM6, writer, capturePoints, insertPoints), 0);
          }
          catch (Exception e) {
            e.printStackTrace();
          }

          byte[] bytes = writer.toByteArray();

          if (DEBUG) {
            try {
              FileOutputStream stream = new FileOutputStream("instrumented_" + className.replaceAll("/", "_") + ".class");
              try {
                stream.write(bytes);
              } finally {
                stream.close();
              }
            }
            catch (IOException e) {
              e.printStackTrace();
            }
          }

          return bytes;
        }
      }
      return null;
    }
  }

  private static class CaptureInstrumentor extends ClassVisitor {
    private final List<CapturePoint> myCapturePoints;
    private final List<InsertPoint> myInsertPoints;

    public CaptureInstrumentor(int api, ClassVisitor cv, List<CapturePoint> capturePoints, List<InsertPoint> insertPoints) {
      super(api, cv);
      this.myCapturePoints = capturePoints;
      this.myInsertPoints = insertPoints;
    }

    private static String getNewName(String name) {
      return name + "$$$capture";
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
      if ((access & Opcodes.ACC_BRIDGE) == 0) {
        for (final CapturePoint capturePoint : myCapturePoints) {
          if (capturePoint.myMethodName.equals(name)) {
            if (DEBUG) {
              System.out.println("Capture agent: instrumented capture point at " + capturePoint.myClassName + "." + name + desc);

            }
            return new MethodVisitor(api, super.visitMethod(access, name, desc, signature, exceptions)) {
              @Override
              public void visitCode() {
                capturePoint.myKeyProvider.loadKey(mv);
                visitMethodInsn(Opcodes.INVOKESTATIC, CaptureStorage.class.getName().replaceAll("\\.", "/"), "capture", "(Ljava/lang/Object;)V", false);
                super.visitCode();
              }
            };
          }
        }

        for (InsertPoint insertPoint : myInsertPoints) {
          if (insertPoint.myMethodName.equals(name)) {
            if (DEBUG) {
              System.out.println("Capture agent: instrumented insert point at " + insertPoint.myClassName + "." +
                      name + desc);
            }
            generateWrapper(access, name, desc, signature, exceptions, insertPoint);
            return super.visitMethod(access, getNewName(name), desc, signature, exceptions);
          }
        }
      }
      return super.visitMethod(access, name, desc, signature, exceptions);
    }

    private void generateWrapper(int access,
                                        String name,
                                        String desc,
                                        String signature,
                                        String[] exceptions,
                                        InsertPoint insertPoint) {
      MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

      Label start = new Label();
      mv.visitLabel(start);

      insertEnter(mv, insertPoint);

      // this
      mv.visitVarInsn(Opcodes.ALOAD, 0);
      // params
      int index = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
      for (Type t : Type.getMethodType(desc).getArgumentTypes()) {
        mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), index);
        index += t.getSize();
      }
      // original call
      mv.visitMethodInsn(Opcodes.INVOKESPECIAL, insertPoint.myClassName, getNewName(insertPoint.myMethodName), desc, false);

      Label end = new Label();
      mv.visitLabel(end);

      // regular exit
      insertExit(mv, insertPoint);
      mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));

      Label catchLabel = new Label();
      mv.visitLabel(catchLabel);
      mv.visitTryCatchBlock(start, end, catchLabel, null);

      // exception exit
      insertExit(mv, insertPoint);
      mv.visitInsn(Opcodes.ATHROW);

      mv.visitMaxs(0, 0);
      mv.visitEnd();
    }

    private static void insertEnter(MethodVisitor mv, InsertPoint insertPoint) {
      insertPoint.myKeyProvider.loadKey(mv);
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, CaptureStorage.class.getName().replaceAll("\\.", "/"), "insertEnter",
                         "(Ljava/lang/Object;)V", false);
    }

    private static void insertExit(MethodVisitor mv, InsertPoint insertPoint) {
      insertPoint.myKeyProvider.loadKey(mv);
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, CaptureStorage.class.getName().replaceAll("\\.", "/"), "insertExit",
                         "(Ljava/lang/Object;)V", false);
    }
  }

  static class CapturePoint {
    final String myClassName;
    final String myMethodName;
    final KeyProvider myKeyProvider;

    public CapturePoint(String className, String methodName, KeyProvider keyProvider) {
      this.myClassName = className;
      this.myMethodName = methodName;
      this.myKeyProvider = keyProvider;
    }
  }

  static class InsertPoint {
    final String myClassName;
    final String myMethodName;
    final KeyProvider myKeyProvider;

    public InsertPoint(String className, String methodName, KeyProvider keyProvider) {
      this.myClassName = className;
      this.myMethodName = methodName;
      this.myKeyProvider = keyProvider;
    }
  }

  // to be run from the debugger
  @SuppressWarnings("unused")
  public static void setCapturePoints(Object[][] capturePoints) throws UnmodifiableClassException {
    Set<String> classNames = new HashSet<String>(myCapturePoints.keySet());

    Map<String, List<CapturePoint>> points = new HashMap<String, List<CapturePoint>>();
    for (Object[] capturePoint : capturePoints) {
      String className = (String)capturePoint[0];
      classNames.add(className);
      List<CapturePoint> currentPoints = points.get(className);
      if (currentPoints == null) {
        currentPoints = new ArrayList<CapturePoint>();
        points.put(className, currentPoints);
      }
      //currentPoints.add(new CapturePoint(className, (String)capturePoint[1], (int)capturePoint[2]));
    }
    myCapturePoints = points;

    List<Class> classes = new ArrayList<Class>(capturePoints.length);
    for (String name : classNames) {
      try {
        classes.add(Class.forName(name));
      }
      catch (ClassNotFoundException e) {
        e.printStackTrace();
      }
    }
    //noinspection SSBasedInspection
    ourInstrumentation.retransformClasses(classes.toArray(new Class[0]));
  }

  private static void addCapturePoint(String className, String methodName, KeyProvider keyProvider) {
    List<CapturePoint> points = myCapturePoints.get(className);
    if (points == null) {
      points = new ArrayList<CapturePoint>();
      myCapturePoints.put(className, points);
    }
    points.add(new CapturePoint(className, methodName, keyProvider));
  }

  private static void addInsertPoint(String className, String methodName, KeyProvider keyProvider) {
    List<InsertPoint> points = myInsertPoints.get(className);
    if (points == null) {
      points = new ArrayList<InsertPoint>();
      myInsertPoints.put(className, points);
    }
    points.add(new InsertPoint(className, methodName, keyProvider));
  }

  private interface KeyProvider {
    void loadKey(MethodVisitor mv);
  }

  private static class FieldKeyProvider implements KeyProvider {
    String myClassName;
    String myFieldName;
    String myFieldDesc;

    public FieldKeyProvider(String className, String fieldName, String fieldDesc) {
      myClassName = className;
      myFieldName = fieldName;
      myFieldDesc = fieldDesc;
    }

    @Override
    public void loadKey(MethodVisitor mv) {
      mv.visitVarInsn(Opcodes.ALOAD, 0);
      mv.visitFieldInsn(Opcodes.GETFIELD, myClassName, myFieldName, myFieldDesc);
    }
  }

  private static class ParamKeyProvider implements KeyProvider {
    int mySlot;

    public ParamKeyProvider(int slot) {
      mySlot = slot;
    }

    @Override
    public void loadKey(MethodVisitor mv) {
      mv.visitVarInsn(Opcodes.ALOAD, mySlot);
    }
  }
}
