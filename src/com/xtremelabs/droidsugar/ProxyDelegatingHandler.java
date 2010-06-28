package com.xtremelabs.droidsugar;

import javassist.*;

import java.lang.reflect.*;
import java.lang.reflect.Modifier;
import java.util.*;

public class ProxyDelegatingHandler implements ClassHandler {
    public static final String PROXY_DELEGATE_FIELD_NAME = "__proxyDelegate__";

    private Map<String, String> classHandlers = new HashMap<String, String>();
    private Map<Class, Field> proxyFieldMap = new HashMap<Class, Field>();
    public boolean debug = false;

    public ProxyDelegatingHandler() {
    }

    @Override
    public void instrument(CtClass ctClass) {
        try {
            CtClass objectClass = ctClass.getClassPool().get(Object.class.getName());
            try {
                ctClass.getField(PROXY_DELEGATE_FIELD_NAME);
            } catch (NotFoundException e) {
                CtField field = new CtField(objectClass, PROXY_DELEGATE_FIELD_NAME, ctClass);
                field.setModifiers(Modifier.PUBLIC);
                ctClass.addField(field);
            }
        } catch (CannotCompileException e) {
            throw new RuntimeException(e);
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void beforeTest() {
        classHandlers.clear();
    }

    @Override
    public void afterTest() {
    }

    public void addProxyClass(Class<?> realClass, Class<?> handlerClass) {
        classHandlers.put(realClass.getName(), handlerClass.getName());
        if (debug) System.out.println("handle " + realClass + " with " + handlerClass);
    }

    @Override
    public Object methodInvoked(String className, String methodName, Object instance, String[] paramTypes, Object[] params) {
        Class<?>[] paramClasses = new Class<?>[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            paramClasses[i] = loadClass(paramTypes[i], instance);
        }

        Class<?> originalClass = loadClass(className, instance);

        Class<?> declaringClass;
        if (methodName.equals("<init>")) {
            declaringClass = originalClass;
        } else {
            Method originalMethod;
            try {
                originalMethod = originalClass.getDeclaredMethod(methodName, paramClasses);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
            declaringClass = originalMethod.getDeclaringClass();
        }
        String handlingClassName = getHandlingClassName(declaringClass);
        if (handlingClassName == null) {
            return null;
        }
        Class<?> handlingClass = loadClass(handlingClassName, instance);

        Method method;
        if (methodName.equals("<init>")) {
            methodName = "__constructor__";
        }

        Object fakeObject = fakeObjectFor(instance);
        try {
            method = fakeObject.getClass().getMethod(methodName, paramClasses);
        } catch (NoSuchMethodException e) {
            if (debug) {
                System.out.println("No method found for " + className + "." + methodName + "(" + Arrays.asList(paramClasses) + ") on " + handlingClass.getName());
            }
            return null;
        }

        try {
            return method.invoke(fakeObject, params);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(fakeObject.getClass().getName() + " is not assignable from " + handlingClass.getName(), e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private Class<?> loadClass(String paramType, Object instance) {
        Class primitiveClass = Type.findPrimitiveClass(paramType);
        if (primitiveClass != null) return primitiveClass;

        int arrayLevel = 0;
        while (paramType.endsWith("[]")) {
            arrayLevel++;
            paramType = paramType.substring(0, paramType.length() - 2);
        }

        Class<?> clazz;
        try {
            clazz = instance.getClass().getClassLoader().loadClass(paramType);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        while (arrayLevel-- > 0) {
            clazz = Array.newInstance(clazz, 0).getClass();
        }

        return clazz;
    }

    public Object fakeObjectFor(Object instance) {
        Field field = getProxyField(instance);
        Object proxy = getField(instance, field);

        if (proxy != null) {
            return proxy;
        }

        String fakeClassName = getHandlingClassName(instance.getClass());

        if (debug)
            System.out.println("creating new " + fakeClassName + " as proxy for " + instance.getClass().getName());
        try {
            Class<?> proxyClass = loadClass(fakeClassName, instance);
            Constructor<?> constructor = findConstructor(instance, proxyClass);
            Object fake;
            if (constructor != null) {
                fake = constructor.newInstance(instance);
            } else {
                fake = proxyClass.newInstance();
            }
            field.set(instance, fake);

            return fake;
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private String getHandlingClassName(Class clazz) {
        String fakeClassName = null;
        while (fakeClassName == null && clazz != null) {
            fakeClassName = classHandlers.get(clazz.getName());
            clazz = clazz.getSuperclass();
        }
        return fakeClassName;
    }

    private Constructor<?> findConstructor(Object instance, Class<?> proxyClass) {
        Class clazz = instance.getClass();

        Constructor constructor;
        for (constructor = null; constructor == null && clazz != null; clazz = clazz.getSuperclass()) {
            try {
                constructor = proxyClass.getConstructor(clazz);
            } catch (NoSuchMethodException e) {
                // expected
            }
        }
        return constructor;
    }

    private Object getField(Object instance, Field field) {
        try {
            return field.get(instance);
        } catch (IllegalAccessException e1) {
            throw new RuntimeException(e1);
        }
    }

    private Field getProxyField(Object instance) {
        Class clazz = instance.getClass();
        Field field = proxyFieldMap.get(clazz);
        if (field == null) {
            try {
                field = clazz.getField(PROXY_DELEGATE_FIELD_NAME);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(instance.getClass().getName() + " has no proxy field", e);
            }
            proxyFieldMap.put(clazz, field);
        }
        return field;
    }

    public Object proxyFor(Object instance) {
        Field field = getProxyField(instance);
        return getField(instance, field);
    }
}