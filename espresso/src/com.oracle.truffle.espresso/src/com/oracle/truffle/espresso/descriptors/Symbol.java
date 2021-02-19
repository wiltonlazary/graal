/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.descriptors;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Date;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.jni.ModifiedUtf8;
import com.oracle.truffle.espresso.meta.EspressoError;

/**
 * An immutable byte string (modified-UTF8) for internal use in Espresso. <br>
 * Symbols are unique during it's lifetime and can be reference-compared for equality. Internal
 * meta-data in Espresso is stored using symbols: names, signatures, type descriptors and constant
 * pool (modified-)UTF8 entries.
 * <p>
 * Symbols provides several advantages over {@link String} for storing meta-data:
 * <ul>
 * <li>Clear separation between guest and host
 * <li>Compact representation
 * <li>Globally unique + cheap equality comparison
 * <li>Seamless de-duplication
 * <li>0-cost tagging for improved type-safety
 * <li>Copy-less symbolification, symbols are only copied once, when they are being
 * created/persisted
 * <li>Contents are effectively {@link CompilationFinal partial-evaluation constant}
 * </ul>
 *
 * Symbols can be tagged, with no runtime cost, for additional type safety:
 * <ul>
 * <li>Symbol&lt;{@link Name}&gt; identifiers, field/class/method names.
 * <li>Symbol&lt;{@link ModifiedUTF8}&gt; strings from the constant pool.
 * <li>Symbol&lt;? extends {@link Descriptor}&gt; valid types or signatures
 * <li>Symbol&lt;{@link Signature}&gt; valid signature descriptor in internal form
 * <li>Symbol&lt;{@link Type}&gt; valid type descriptor in internal form
 * </ul>
 *
 * <b> Note: Do not synchronize on symbols; class loading relies on exclusive ownership of the
 * symbol's monitors, it may cause unexpected dead-locks. </b>
 *
 * @param <T> generic tag for improved type-safety
 */
public final class Symbol<T> extends ByteSequence {

    @SuppressWarnings("rawtypes") public static final Symbol[] EMPTY_ARRAY = new Symbol[0];

    Symbol(byte[] bytes, int hashCode) {
        super(bytes, hashCode);
    }

    Symbol(byte[] value) {
        // hashCode must match ByteSequence.hashCodeOfRange.
        this(value, Arrays.hashCode(value));
    }

    @SuppressWarnings("unchecked")
    public static <S> Symbol<S>[] emptyArray() {
        return EMPTY_ARRAY;
    }

    ByteSequence substring(int from) {
        return substring(from, length());
    }

    ByteSequence substring(int from, int to) {
        assert 0 <= from && from <= to && to <= length();
        if (from == 0 && to == length()) {
            return this;
        }
        return subSequence(from, to - from);
    }

    static void copyBytes(Symbol<?> src, int srcPos, byte[] dest, int destPos, int length) {
        System.arraycopy(src.value, srcPos, dest, destPos, length);
    }

    @Override
    public byte byteAt(int index) {
        return value[index];
    }

    @TruffleBoundary
    @Override
    public String toString() {
        try {
            return ModifiedUtf8.toJavaString(value);
        } catch (IOException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    @Override
    public int length() {
        return value.length;
    }

    @Override
    public int offset() {
        return 0;
    }

    @Override
    public boolean equals(Object that) {
        return this == that;
    }

    public static class ModifiedUTF8 {

        @SuppressWarnings("unchecked")
        public static Symbol<ModifiedUTF8> fromSymbol(Symbol<? extends ModifiedUTF8> symbol) {
            return (Symbol<ModifiedUTF8>) symbol;
        }
    }

    public static class Descriptor extends ModifiedUTF8 {
    }

    /**
     * Contains commonly used (name) symbols.
     *
     * Symbols declared here must match exactly the field name; notable exceptions include
     * {@link #_init_}, {@link #_clinit_} and hidden field names.
     */
    public static final class Name extends ModifiedUTF8 {

        public static void ensureInitialized() {
            /* nop */
        }

        // java.base
        public static final Symbol<Name> java_base = StaticSymbols.putName("java.base");

        // general
        public static final Symbol<Name> _init_ = StaticSymbols.putName("<init>");
        public static final Symbol<Name> _clinit_ = StaticSymbols.putName("<clinit>");

        // Boxing and String
        public static final Symbol<Name> value = StaticSymbols.putName("value");
        public static final Symbol<Name> valueOf = StaticSymbols.putName("valueOf");
        public static final Symbol<Name> booleanValue = StaticSymbols.putName("booleanValue");
        public static final Symbol<Name> byteValue = StaticSymbols.putName("byteValue");
        public static final Symbol<Name> shortValue = StaticSymbols.putName("shortValue");
        public static final Symbol<Name> intValue = StaticSymbols.putName("intValue");
        public static final Symbol<Name> longValue = StaticSymbols.putName("longValue");
        public static final Symbol<Name> floatValue = StaticSymbols.putName("floatValue");
        public static final Symbol<Name> doubleValue = StaticSymbols.putName("doubleValue");

        // Field, Thread, Module and MemberName
        public static final Symbol<Name> name = StaticSymbols.putName("name");
        // Thread and Runnable
        public static final Symbol<Name> run = StaticSymbols.putName("run");
        // Thread and System
        public static final Symbol<Name> exit = StaticSymbols.putName("exit");
        // Object and arrays
        public static final Symbol<Name> clone = StaticSymbols.putName("clone");
        public static final Symbol<Name> toString = StaticSymbols.putName("toString");
        // variable 'this' name
        public static final Symbol<Name> thiz = StaticSymbols.putName("this");

        // finding main
        public static final Symbol<Name> checkAndLoadMain = StaticSymbols.putName("checkAndLoadMain");
        public static final Symbol<Name> main = StaticSymbols.putName("main");

        // Reflection
        public static final Symbol<Name> clazz = StaticSymbols.putName("clazz");
        public static final Symbol<Name> getParameterTypes = StaticSymbols.putName("getParameterTypes");
        public static final Symbol<Name> override = StaticSymbols.putName("override");
        public static final Symbol<Name> parameterTypes = StaticSymbols.putName("parameterTypes");
        public static final Symbol<Name> root = StaticSymbols.putName("root");
        public static final Symbol<Name> signature = StaticSymbols.putName("signature");
        public static final Symbol<Name> slot = StaticSymbols.putName("slot");
        public static final Symbol<Name> type = StaticSymbols.putName("type");

        // java.lang.*
        // j.l.AssertionStatusDirectives
        public static final Symbol<Name> classes = StaticSymbols.putName("classes");
        public static final Symbol<Name> classEnabled = StaticSymbols.putName("classEnabled");
        public static final Symbol<Name> deflt = StaticSymbols.putName("deflt");
        public static final Symbol<Name> packages = StaticSymbols.putName("packages");
        public static final Symbol<Name> packageEnabled = StaticSymbols.putName("packageEnabled");

        // j.l.Class
        public static final Symbol<Name> checkPackageAccess = StaticSymbols.putName("checkPackageAccess");
        public static final Symbol<Name> getName = StaticSymbols.putName("getName");
        public static final Symbol<Name> getSimpleName = StaticSymbols.putName("getSimpleName");
        public static final Symbol<Name> getTypeName = StaticSymbols.putName("getTypeName");
        public static final Symbol<Name> forName = StaticSymbols.putName("forName");
        public static final Symbol<Name> module = StaticSymbols.putName("module");
        public static final Symbol<Name> classLoader = StaticSymbols.putName("classLoader");
        public static final Symbol<Name> classRedefinedCount = StaticSymbols.putName("classRedefinedCount");

        // j.l.ClassLoader
        public static final Symbol<Name> addClass = StaticSymbols.putName("addClass");
        public static final Symbol<Name> findNative = StaticSymbols.putName("findNative");
        public static final Symbol<Name> getSystemClassLoader = StaticSymbols.putName("getSystemClassLoader");
        public static final Symbol<Name> loadClass = StaticSymbols.putName("loadClass");
        public static final Symbol<Name> getResourceAsStream = StaticSymbols.putName("getResourceAsStream");
        public static final Symbol<Name> parent = StaticSymbols.putName("parent");
        public static final Symbol<Name> unnamedModule = StaticSymbols.putName("unnamedModule");
        public static final Symbol<Name> HIDDEN_CLASS_LOADER_REGISTRY = StaticSymbols.putName("0HIDDEN_CLASS_LOADER_REGISTRY");

        // j.l.Module
        public static final Symbol<Name> loader = StaticSymbols.putName("loader");

        // j.l.String
        public static final Symbol<Name> hash = StaticSymbols.putName("hash");
        public static final Symbol<Name> hashCode = StaticSymbols.putName("hashCode");
        public static final Symbol<Name> length = StaticSymbols.putName("length");
        public static final Symbol<Name> toCharArray = StaticSymbols.putName("toCharArray");
        public static final Symbol<Name> charAt = StaticSymbols.putName("charAt");
        public static final Symbol<Name> coder = StaticSymbols.putName("coder");
        public static final Symbol<Name> COMPACT_STRINGS = StaticSymbols.putName("COMPACT_STRINGS");
        public static final Symbol<Name> indexOf = StaticSymbols.putName("indexOf");

        // j.l.Throwable
        public static final Symbol<Name> backtrace = StaticSymbols.putName("backtrace");
        public static final Symbol<Name> cause = StaticSymbols.putName("cause");
        public static final Symbol<Name> depth = StaticSymbols.putName("depth");
        public static final Symbol<Name> fillInStackTrace = StaticSymbols.putName("fillInStackTrace");
        public static final Symbol<Name> fillInStackTrace0 = StaticSymbols.putName("fillInStackTrace0");
        public static final Symbol<Name> getMessage = StaticSymbols.putName("getMessage");
        public static final Symbol<Name> detailMessage = StaticSymbols.putName("detailMessage");
        public static final Symbol<Name> printStackTrace = StaticSymbols.putName("printStackTrace");

        // j.l.Thread
        public static final Symbol<Name> add = StaticSymbols.putName("add");
        public static final Symbol<Name> blockerLock = StaticSymbols.putName("blockerLock");
        public static final Symbol<Name> checkAccess = StaticSymbols.putName("checkAccess");
        public static final Symbol<Name> daemon = StaticSymbols.putName("daemon");
        public static final Symbol<Name> dispatchUncaughtException = StaticSymbols.putName("dispatchUncaughtException");
        public static final Symbol<Name> getStackTrace = StaticSymbols.putName("getStackTrace");
        public static final Symbol<Name> group = StaticSymbols.putName("group");
        public static final Symbol<Name> inheritedAccessControlContext = StaticSymbols.putName("inheritedAccessControlContext");
        public static final Symbol<Name> maxPriority = StaticSymbols.putName("maxPriority");
        public static final Symbol<Name> parkBlocker = StaticSymbols.putName("parkBlocker");
        public static final Symbol<Name> priority = StaticSymbols.putName("priority");
        public static final Symbol<Name> remove = StaticSymbols.putName("remove");
        public static final Symbol<Name> stop = StaticSymbols.putName("stop");
        public static final Symbol<Name> threadStatus = StaticSymbols.putName("threadStatus");
        public static final Symbol<Name> toThreadState = StaticSymbols.putName("toThreadState");

        // j.l.StackTraceElement
        public static final Symbol<Name> declaringClassObject = StaticSymbols.putName("declaringClassObject");
        public static final Symbol<Name> classLoaderName = StaticSymbols.putName("classLoaderName");
        public static final Symbol<Name> moduleName = StaticSymbols.putName("moduleName");
        public static final Symbol<Name> moduleVersion = StaticSymbols.putName("moduleVersion");
        public static final Symbol<Name> declaringClass = StaticSymbols.putName("declaringClass");
        public static final Symbol<Name> methodName = StaticSymbols.putName("methodName");
        public static final Symbol<Name> fileName = StaticSymbols.putName("fileName");
        public static final Symbol<Name> lineNumber = StaticSymbols.putName("lineNumber");

        // j.l.System
        public static final Symbol<Name> getProperty = StaticSymbols.putName("getProperty");
        public static final Symbol<Name> initializeSystemClass = StaticSymbols.putName("initializeSystemClass");
        public static final Symbol<Name> initPhase1 = StaticSymbols.putName("initPhase1");
        public static final Symbol<Name> initPhase2 = StaticSymbols.putName("initPhase2");
        public static final Symbol<Name> initPhase3 = StaticSymbols.putName("initPhase3");
        public static final Symbol<Name> security = StaticSymbols.putName("security");
        public static final Symbol<Name> setProperty = StaticSymbols.putName("setProperty");

        // j.l.Shutdown
        public static final Symbol<Name> shutdown = StaticSymbols.putName("shutdown");

        // j.l.StackStreamFactory
        public static final Symbol<Name> doStackWalk = StaticSymbols.putName("doStackWalk");
        public static final Symbol<Name> callStackWalk = StaticSymbols.putName("callStackWalk");

        // j.l.StackFrameInfo
        public static final Symbol<Name> memberName = StaticSymbols.putName("memberName");
        public static final Symbol<Name> bci = StaticSymbols.putName("bci");

        // java.nio.ByteBuffer
        public static final Symbol<Name> wrap = StaticSymbols.putName("wrap");

        // java.nio.Buffer
        public static final Symbol<Name> address = StaticSymbols.putName("address");
        public static final Symbol<Name> capacity = StaticSymbols.putName("capacity");
        public static final Symbol<Name> wait = StaticSymbols.putName("wait");

        // java.io.InputStream
        public static final Symbol<Name> available = StaticSymbols.putName("available");
        public static final Symbol<Name> read = StaticSymbols.putName("read");
        public static final Symbol<Name> close = StaticSymbols.putName("close");

        // java.io.PrintStream
        public static final Symbol<Name> println = StaticSymbols.putName("println");

        // java.lang.invoke.*
        // CallSite
        public static final Symbol<Name> target = StaticSymbols.putName("target");

        // LambdaForm
        public static final Symbol<Name> compileToBytecode = StaticSymbols.putName("compileToBytecode");
        public static final Symbol<Name> isCompiled = StaticSymbols.putName("isCompiled");
        public static final Symbol<Name> vmentry = StaticSymbols.putName("vmentry");
        public static final Symbol<Name> getCallerClass = StaticSymbols.putName("getCallerClass");

        public static final Symbol<Name> createMemoryPool = StaticSymbols.putName("createMemoryPool");
        public static final Symbol<Name> createMemoryManager = StaticSymbols.putName("createMemoryManager");
        public static final Symbol<Name> createGarbageCollector = StaticSymbols.putName("createGarbageCollector");
        public static final Symbol<Name> tid = StaticSymbols.putName("tid");
        public static final Symbol<Name> getFromClass = StaticSymbols.putName("getFromClass");

        // MemberName
        public static final Symbol<Name> flags = StaticSymbols.putName("flags");
        public static final Symbol<Name> form = StaticSymbols.putName("form");
        public static final Symbol<Name> getSignature = StaticSymbols.putName("getSignature");

        // MethodHandle
        public static final Symbol<Name> invoke = StaticSymbols.putName("invoke");
        public static final Symbol<Name> invokeExact = StaticSymbols.putName("invokeExact");
        public static final Symbol<Name> invokeBasic = StaticSymbols.putName("invokeBasic");
        public static final Symbol<Name> invokeWithArguments = StaticSymbols.putName("invokeWithArguments");
        public static final Symbol<Name> linkToVirtual = StaticSymbols.putName("linkToVirtual");
        public static final Symbol<Name> linkToStatic = StaticSymbols.putName("linkToStatic");
        public static final Symbol<Name> linkToInterface = StaticSymbols.putName("linkToInterface");
        public static final Symbol<Name> linkToSpecial = StaticSymbols.putName("linkToSpecial");

        // MethodHandleNatives
        public static final Symbol<Name> findMethodHandleType = StaticSymbols.putName("findMethodHandleType");
        public static final Symbol<Name> linkMethod = StaticSymbols.putName("linkMethod");
        public static final Symbol<Name> linkCallSite = StaticSymbols.putName("linkCallSite");
        public static final Symbol<Name> linkDynamicConstant = StaticSymbols.putName("linkDynamicConstant");
        public static final Symbol<Name> linkMethodHandleConstant = StaticSymbols.putName("linkMethodHandleConstant");

        // MethodHandles
        public static final Symbol<Name> lookup = StaticSymbols.putName("lookup");

        // MethodType
        public static final Symbol<Name> fromMethodDescriptorString = StaticSymbols.putName("fromMethodDescriptorString");
        public static final Symbol<Name> toMethodDescriptorString = StaticSymbols.putName("toMethodDescriptorString");

        // j.l.ref.Finalizer
        public static final Symbol<Name> finalize = StaticSymbols.putName("finalize");
        public static final Symbol<Name> register = StaticSymbols.putName("register");

        // j.l.ref.Reference
        public static final Symbol<Name> discovered = StaticSymbols.putName("discovered");
        public static final Symbol<Name> enqueue = StaticSymbols.putName("enqueue");
        public static final Symbol<Name> lock = StaticSymbols.putName("lock");
        public static final Symbol<Name> next = StaticSymbols.putName("next");
        public static final Symbol<Name> NULL = StaticSymbols.putName("NULL");
        public static final Symbol<Name> pending = StaticSymbols.putName("pending");
        public static final Symbol<Name> processPendingLock = StaticSymbols.putName("processPendingLock");
        public static final Symbol<Name> queue = StaticSymbols.putName("queue");
        public static final Symbol<Name> referent = StaticSymbols.putName("referent");

        // java.security.ProtectionDomain
        public static final Symbol<Name> impliesCreateAccessControlContext = StaticSymbols.putName("impliesCreateAccessControlContext");

        // java.security.AccessControlContext
        public static final Symbol<Name> context = StaticSymbols.putName("context");
        public static final Symbol<Name> isAuthorized = StaticSymbols.putName("isAuthorized");
        public static final Symbol<Name> isPrivileged = StaticSymbols.putName("isPrivileged");
        public static final Symbol<Name> privilegedContext = StaticSymbols.putName("privilegedContext");
        public static final Symbol<Name> doPrivileged = StaticSymbols.putName("doPrivileged");

        // sun.launcher.LauncherHelper
        public static final Symbol<Name> printHelpMessage = StaticSymbols.putName("printHelpMessage");
        public static final Symbol<Name> ostream = StaticSymbols.putName("ostream");

        // sun.reflect.ConstantPool
        public static final Symbol<Name> constantPoolOop = StaticSymbols.putName("constantPoolOop");

        // Attribute names
        public static final Symbol<Name> AnnotationDefault = StaticSymbols.putName("AnnotationDefault");
        public static final Symbol<Name> BootstrapMethods = StaticSymbols.putName("BootstrapMethods");
        public static final Symbol<Name> Code = StaticSymbols.putName("Code");
        public static final Symbol<Name> ConstantValue = StaticSymbols.putName("ConstantValue");
        public static final Symbol<Name> Deprecated = StaticSymbols.putName("Deprecated");
        public static final Symbol<Name> EnclosingMethod = StaticSymbols.putName("EnclosingMethod");
        public static final Symbol<Name> Exceptions = StaticSymbols.putName("Exceptions");
        public static final Symbol<Name> InnerClasses = StaticSymbols.putName("InnerClasses");
        public static final Symbol<Name> LineNumberTable = StaticSymbols.putName("LineNumberTable");
        public static final Symbol<Name> LocalVariableTable = StaticSymbols.putName("LocalVariableTable");
        public static final Symbol<Name> LocalVariableTypeTable = StaticSymbols.putName("LocalVariableTypeTable");
        public static final Symbol<Name> MethodParameters = StaticSymbols.putName("MethodParameters");
        public static final Symbol<Name> NestHost = StaticSymbols.putName("NestHost");
        public static final Symbol<Name> NestMembers = StaticSymbols.putName("NestMembers");
        public static final Symbol<Name> RuntimeVisibleAnnotations = StaticSymbols.putName("RuntimeVisibleAnnotations");
        public static final Symbol<Name> RuntimeInvisibleAnnotations = StaticSymbols.putName("RuntimeInvisibleAnnotations");
        public static final Symbol<Name> RuntimeVisibleTypeAnnotations = StaticSymbols.putName("RuntimeVisibleTypeAnnotations");
        public static final Symbol<Name> RuntimeInvisibleTypeAnnotations = StaticSymbols.putName("RuntimeInvisibleTypeAnnotations");
        public static final Symbol<Name> RuntimeVisibleParameterAnnotations = StaticSymbols.putName("RuntimeVisibleParameterAnnotations");
        public static final Symbol<Name> RuntimeInvisibleParameterAnnotations = StaticSymbols.putName("RuntimeInvisibleParameterAnnotations");
        public static final Symbol<Name> Signature = StaticSymbols.putName("Signature");
        public static final Symbol<Name> SourceFile = StaticSymbols.putName("SourceFile");
        public static final Symbol<Name> SourceDebugExtension = StaticSymbols.putName("SourceDebugExtension");
        public static final Symbol<Name> StackMapTable = StaticSymbols.putName("StackMapTable");
        public static final Symbol<Name> Synthetic = StaticSymbols.putName("Synthetic");

        // Interop conversions
        public static final Symbol<Name> seconds = StaticSymbols.putName("seconds");
        public static final Symbol<Name> nanos = StaticSymbols.putName("nanos");
        public static final Symbol<Name> year = StaticSymbols.putName("year");
        public static final Symbol<Name> month = StaticSymbols.putName("month");
        public static final Symbol<Name> day = StaticSymbols.putName("day");
        public static final Symbol<Name> toLocalDate = StaticSymbols.putName("toLocalDate");
        public static final Symbol<Name> toLocalTime = StaticSymbols.putName("toLocalTime");
        public static final Symbol<Name> toInstant = StaticSymbols.putName("toInstant");
        public static final Symbol<Name> getZone = StaticSymbols.putName("getZone");
        public static final Symbol<Name> getId = StaticSymbols.putName("getId");
        public static final Symbol<Name> of = StaticSymbols.putName("of");

        public static final Symbol<Name> hour = StaticSymbols.putName("hour");
        public static final Symbol<Name> minute = StaticSymbols.putName("minute");
        public static final Symbol<Name> second = StaticSymbols.putName("second");
        public static final Symbol<Name> nano = StaticSymbols.putName("nano");
        public static final Symbol<Name> atZone = StaticSymbols.putName("atZone");

        // Hidden field names. Starts with a 0 in order for the names to be illegal identifiers.

        // MemberName
        public static final Symbol<Name> HIDDEN_VMINDEX = StaticSymbols.putName("0HIDDEN_VMINDEX");
        public static final Symbol<Name> HIDDEN_VMTARGET = StaticSymbols.putName("0HIDDEN_VMTARGET");

        // Method
        public static final Symbol<Name> HIDDEN_METHOD_KEY = StaticSymbols.putName("0HIDDEN_METHOD_KEY");
        public static final Symbol<Name> HIDDEN_METHOD_RUNTIME_VISIBLE_TYPE_ANNOTATIONS = StaticSymbols.putName("0HIDDEN_METHOD_RUNTIME_VISIBLE_TYPE_ANNOTATIONS");

        // Constructor
        public static final Symbol<Name> HIDDEN_CONSTRUCTOR_KEY = StaticSymbols.putName("0HIDDEN_CONSTRUCTOR_KEY");
        public static final Symbol<Name> HIDDEN_CONSTRUCTOR_RUNTIME_VISIBLE_TYPE_ANNOTATIONS = StaticSymbols.putName("0HIDDEN_CONSTRUCTOR_RUNTIME_VISIBLE_TYPE_ANNOTATIONS");

        // Field
        public static final Symbol<Name> HIDDEN_FIELD_KEY = StaticSymbols.putName("0HIDDEN_FIELD_KEY");
        public static final Symbol<Name> HIDDEN_FIELD_RUNTIME_VISIBLE_TYPE_ANNOTATIONS = StaticSymbols.putName("0HIDDEN_FIELD_RUNTIME_VISIBLE_TYPE_ANNOTATIONS");

        // Throwable
        public static final Symbol<Name> HIDDEN_FRAMES = StaticSymbols.putName("0HIDDEN_FRAMES");

        // Thread
        public static final Symbol<Name> HIDDEN_DEATH = StaticSymbols.putName("0HIDDEN_DEATH");
        public static final Symbol<Name> HIDDEN_DEATH_THROWABLE = StaticSymbols.putName("0HIDDEN_DEATH_THROWABLE");
        public static final Symbol<Name> HIDDEN_HOST_THREAD = StaticSymbols.putName("0HIDDEN_HOST_THREAD");
        public static final Symbol<Name> HIDDEN_INTERRUPTED = StaticSymbols.putName("0HIDDEN_INTERRUPTED");
        public static final Symbol<Name> HIDDEN_IS_ALIVE = StaticSymbols.putName("0HIDDEN_IS_ALIVE");
        public static final Symbol<Name> HIDDEN_SUSPEND_LOCK = StaticSymbols.putName("0HIDDEN_SUSPEND_LOCK");

        // Class
        public static final Symbol<Name> HIDDEN_MIRROR_KLASS = StaticSymbols.putName("0HIDDEN_MIRROR_KLASS");
        public static final Symbol<Name> HIDDEN_SIGNERS = StaticSymbols.putName("0HIDDEN_SIGNERS");
        public static final Symbol<Name> HIDDEN_PROTECTION_DOMAIN = StaticSymbols.putName("0HIDDEN_PROTECTION_DOMAIN");

        // Module
        public static final Symbol<Name> HIDDEN_MODULE_ENTRY = StaticSymbols.putName("0HIDDEN_MODULE_ENTRY");

        // Reference
        public static final Symbol<Name> HIDDEN_HOST_REFERENCE = StaticSymbols.putName("0HIDDEN_HOST_REFERENCE");
        public static final Symbol<Name> HIDDEN_THREAD_BLOCKED_OBJECT = StaticSymbols.putName("0HIDDEN_THREAD_BLOCKED_OBJECT");
        public static final Symbol<Name> HIDDEN_THREAD_BLOCKED_COUNT = StaticSymbols.putName("0HIDDEN_THREAD_BLOCKED_COUNT");
        public static final Symbol<Name> HIDDEN_THREAD_WAITED_COUNT = StaticSymbols.putName("0HIDDEN_THREAD_WAITED_COUNT");

        // Polyglot ExceptionType
        public static final Symbol<Name> EXIT = StaticSymbols.putName("EXIT");
        public static final Symbol<Name> INTERRUPT = StaticSymbols.putName("INTERRUPT");
        public static final Symbol<Name> RUNTIME_ERROR = StaticSymbols.putName("RUNTIME_ERROR");
        public static final Symbol<Name> PARSE_ERROR = StaticSymbols.putName("PARSE_ERROR");
        public static final Symbol<Name> create = StaticSymbols.putName("create");

    }

    /**
     * Contains commonly used (type) symbols.
     *
     * <p>
     * Naming convention: Use the fully qualified type name, '_' as package separator and '$' as
     * separator for inner classes.<br>
     * - {@link #_long} {@ling #java_lang}<br>
     * - {@link #java_lang_String_array}<br>
     * - {@link #_int_array_array}<br>
     * - {@link #java_lang_ref_Finalizer$FinalizerThread}<br>
     */
    public static final class Type extends Descriptor {

        public static void ensureInitialized() {
            /* nop */
        }

        // Core types.
        public static final Symbol<Type> java_lang_String = StaticSymbols.putType(String.class);
        public static final Symbol<Type> java_lang_String_array = StaticSymbols.putType(String[].class);

        public static final Symbol<Type> java_lang_Object = StaticSymbols.putType(Object.class);
        public static final Symbol<Type> java_lang_Object_array = StaticSymbols.putType(Object[].class);

        public static final Symbol<Type> java_lang_Class = StaticSymbols.putType(Class.class);
        public static final Symbol<Type> java_lang_Class_array = StaticSymbols.putType(Class[].class);

        public static final Symbol<Type> java_lang_Throwable = StaticSymbols.putType(Throwable.class);
        public static final Symbol<Type> java_lang_Exception = StaticSymbols.putType(Exception.class);
        public static final Symbol<Type> java_lang_System = StaticSymbols.putType(System.class);
        public static final Symbol<Type> java_security_ProtectionDomain = StaticSymbols.putType(java.security.ProtectionDomain.class);
        public static final Symbol<Type> java_security_ProtectionDomain_array = StaticSymbols.putType(java.security.ProtectionDomain[].class);
        public static final Symbol<Type> java_security_AccessControlContext = StaticSymbols.putType(java.security.AccessControlContext.class);
        public static final Symbol<Type> java_security_AccessController = StaticSymbols.putType(java.security.AccessController.class);
        public static final Symbol<Type> java_lang_SecurityManager = StaticSymbols.putType(SecurityManager.class);
        public static final Symbol<Type> java_security_CodeSource = StaticSymbols.putType(java.security.CodeSource.class);
        public static final Symbol<Type> java_security_PermissionCollection = StaticSymbols.putType(java.security.PermissionCollection.class);

        public static final Symbol<Type> java_lang_ClassLoader = StaticSymbols.putType(java.lang.ClassLoader.class);
        public static final Symbol<Type> java_lang_ClassLoader$NativeLibrary = StaticSymbols.putType("Ljava/lang/ClassLoader$NativeLibrary;");
        public static final Symbol<Type> sun_misc_Launcher$ExtClassLoader = StaticSymbols.putType("Lsun/misc/Launcher$ExtClassLoader;");

        public static final Symbol<Type> java_io_InputStream = StaticSymbols.putType(InputStream.class);
        public static final Symbol<Type> java_io_PrintStream = StaticSymbols.putType(PrintStream.class);

        public static final Symbol<Type> jdk_internal_loader_ClassLoaders$PlatformClassLoader = StaticSymbols.putType("Ljdk/internal/loader/ClassLoaders$PlatformClassLoader;");

        // Primitive types.
        public static final Symbol<Type> _boolean = StaticSymbols.putType(boolean.class);
        public static final Symbol<Type> _byte = StaticSymbols.putType(byte.class);
        public static final Symbol<Type> _char = StaticSymbols.putType(char.class);
        public static final Symbol<Type> _short = StaticSymbols.putType(short.class);
        public static final Symbol<Type> _int = StaticSymbols.putType(int.class);
        public static final Symbol<Type> _float = StaticSymbols.putType(float.class);
        public static final Symbol<Type> _double = StaticSymbols.putType(double.class);
        public static final Symbol<Type> _long = StaticSymbols.putType(long.class);
        public static final Symbol<Type> _void = StaticSymbols.putType(void.class);

        public static final Symbol<Type> _boolean_array = StaticSymbols.putType(boolean[].class);
        public static final Symbol<Type> _byte_array = StaticSymbols.putType(byte[].class);
        public static final Symbol<Type> _char_array = StaticSymbols.putType(char[].class);
        public static final Symbol<Type> _short_array = StaticSymbols.putType(short[].class);
        public static final Symbol<Type> _int_array = StaticSymbols.putType(int[].class);
        public static final Symbol<Type> _float_array = StaticSymbols.putType(float[].class);
        public static final Symbol<Type> _double_array = StaticSymbols.putType(double[].class);
        public static final Symbol<Type> _long_array = StaticSymbols.putType(long[].class);

        public static final Symbol<Type> _int_array_array = StaticSymbols.putType(int[][].class);

        // Boxed types.
        public static final Symbol<Type> java_lang_Boolean = StaticSymbols.putType(Boolean.class);
        public static final Symbol<Type> java_lang_Byte = StaticSymbols.putType(Byte.class);
        public static final Symbol<Type> java_lang_Character = StaticSymbols.putType(Character.class);
        public static final Symbol<Type> java_lang_Short = StaticSymbols.putType(Short.class);
        public static final Symbol<Type> java_lang_Integer = StaticSymbols.putType(Integer.class);
        public static final Symbol<Type> java_lang_Float = StaticSymbols.putType(Float.class);
        public static final Symbol<Type> java_lang_Double = StaticSymbols.putType(Double.class);
        public static final Symbol<Type> java_lang_Long = StaticSymbols.putType(Long.class);
        public static final Symbol<Type> java_lang_Void = StaticSymbols.putType(Void.class);

        public static final Symbol<Type> java_lang_Cloneable = StaticSymbols.putType(Cloneable.class);

        public static final Symbol<Type> java_lang_StackOverflowError = StaticSymbols.putType(StackOverflowError.class);
        public static final Symbol<Type> java_lang_OutOfMemoryError = StaticSymbols.putType(OutOfMemoryError.class);
        public static final Symbol<Type> java_lang_AssertionError = StaticSymbols.putType(AssertionError.class);

        public static final Symbol<Type> java_lang_NullPointerException = StaticSymbols.putType(NullPointerException.class);
        public static final Symbol<Type> java_lang_ClassCastException = StaticSymbols.putType(ClassCastException.class);
        public static final Symbol<Type> java_lang_ArrayStoreException = StaticSymbols.putType(ArrayStoreException.class);
        public static final Symbol<Type> java_lang_ArithmeticException = StaticSymbols.putType(ArithmeticException.class);
        public static final Symbol<Type> java_lang_IllegalMonitorStateException = StaticSymbols.putType(IllegalMonitorStateException.class);
        public static final Symbol<Type> java_lang_IllegalArgumentException = StaticSymbols.putType(IllegalArgumentException.class);
        public static final Symbol<Type> java_lang_ClassNotFoundException = StaticSymbols.putType(ClassNotFoundException.class);
        public static final Symbol<Type> java_lang_NoClassDefFoundError = StaticSymbols.putType(NoClassDefFoundError.class);
        public static final Symbol<Type> java_lang_InterruptedException = StaticSymbols.putType(InterruptedException.class);
        public static final Symbol<Type> java_lang_ThreadDeath = StaticSymbols.putType(ThreadDeath.class);
        public static final Symbol<Type> java_lang_NegativeArraySizeException = StaticSymbols.putType(NegativeArraySizeException.class);
        public static final Symbol<Type> java_lang_RuntimeException = StaticSymbols.putType(RuntimeException.class);
        public static final Symbol<Type> java_lang_reflect_InvocationTargetException = StaticSymbols.putType(java.lang.reflect.InvocationTargetException.class);
        public static final Symbol<Type> java_lang_IndexOutOfBoundsException = StaticSymbols.putType(IndexOutOfBoundsException.class);
        public static final Symbol<Type> java_lang_ArrayIndexOutOfBoundsException = StaticSymbols.putType(ArrayIndexOutOfBoundsException.class);
        public static final Symbol<Type> java_lang_StringIndexOutOfBoundsException = StaticSymbols.putType(StringIndexOutOfBoundsException.class);
        public static final Symbol<Type> java_lang_ExceptionInInitializerError = StaticSymbols.putType(ExceptionInInitializerError.class);
        public static final Symbol<Type> java_lang_InstantiationException = StaticSymbols.putType(InstantiationException.class);
        public static final Symbol<Type> java_lang_InstantiationError = StaticSymbols.putType(InstantiationError.class);
        public static final Symbol<Type> java_lang_CloneNotSupportedException = StaticSymbols.putType(CloneNotSupportedException.class);
        public static final Symbol<Type> java_lang_SecurityException = StaticSymbols.putType(SecurityException.class);
        public static final Symbol<Type> java_lang_LinkageError = StaticSymbols.putType(LinkageError.class);
        public static final Symbol<Type> java_lang_BootstrapMethodError = StaticSymbols.putType(BootstrapMethodError.class);
        public static final Symbol<Type> java_lang_NoSuchFieldException = StaticSymbols.putType(NoSuchFieldException.class);
        public static final Symbol<Type> java_lang_NoSuchMethodException = StaticSymbols.putType(NoSuchMethodException.class);
        public static final Symbol<Type> java_lang_UnsupportedOperationException = StaticSymbols.putType(UnsupportedOperationException.class);
        public static final Symbol<Type> java_lang_UnsupportedClassVersionError = StaticSymbols.putType(UnsupportedClassVersionError.class);

        public static final Symbol<Type> java_lang_Thread = StaticSymbols.putType(Thread.class);
        public static final Symbol<Type> java_lang_ThreadGroup = StaticSymbols.putType(ThreadGroup.class);
        public static final Symbol<Type> java_lang_Runnable = StaticSymbols.putType(Runnable.class);

        public static final Symbol<Type> sun_misc_VM = StaticSymbols.putType("Lsun/misc/VM;");
        public static final Symbol<Type> jdk_internal_misc_VM = StaticSymbols.putType("Ljdk/internal/misc/VM;");
        public static final Symbol<Type> java_lang_Thread$State = StaticSymbols.putType(Thread.State.class);

        public static final Symbol<Type> sun_nio_ch_DirectBuffer = StaticSymbols.putType(sun.nio.ch.DirectBuffer.class);
        public static final Symbol<Type> java_nio_Buffer = StaticSymbols.putType(java.nio.Buffer.class);

        // Guest reflection.
        public static final Symbol<Type> java_lang_reflect_Field = StaticSymbols.putType(java.lang.reflect.Field.class);
        public static final Symbol<Type> java_lang_reflect_Method = StaticSymbols.putType(java.lang.reflect.Method.class);
        public static final Symbol<Type> java_lang_reflect_Constructor = StaticSymbols.putType(java.lang.reflect.Constructor.class);
        public static final Symbol<Type> java_lang_reflect_Parameter = StaticSymbols.putType(java.lang.reflect.Parameter.class);
        public static final Symbol<Type> java_lang_reflect_Executable = StaticSymbols.putType(java.lang.reflect.Executable.class);
        public static final Symbol<Type> sun_reflect_Reflection = StaticSymbols.putType("Lsun/reflect/Reflection;");
        public static final Symbol<Type> jdk_internal_reflect_Reflection = StaticSymbols.putType("Ljdk/internal/reflect/Reflection;");

        // MagicAccessorImpl is not public.
        public static final Symbol<Type> sun_reflect_MagicAccessorImpl = StaticSymbols.putType("Lsun/reflect/MagicAccessorImpl;");
        public static final Symbol<Type> jdk_internal_reflect_MagicAccessorImpl = StaticSymbols.putType("Ljdk/internal/reflect/MagicAccessorImpl;");
        // DelegatingClassLoader is not public.
        public static final Symbol<Type> sun_reflect_DelegatingClassLoader = StaticSymbols.putType("Lsun/reflect/DelegatingClassLoader;");
        public static final Symbol<Type> jdk_internal_reflect_DelegatingClassLoader = StaticSymbols.putType("Ljdk/internal/reflect/DelegatingClassLoader;");

        // MethodAccessorImpl is not public.
        public static final Symbol<Type> sun_reflect_MethodAccessorImpl = StaticSymbols.putType("Lsun/reflect/MethodAccessorImpl;");
        public static final Symbol<Type> jdk_internal_reflect_MethodAccessorImpl = StaticSymbols.putType("Ljdk/internal/reflect/MethodAccessorImpl;");
        public static final Symbol<Type> sun_reflect_ConstructorAccessorImpl = StaticSymbols.putType("Lsun/reflect/ConstructorAccessorImpl;");
        public static final Symbol<Type> jdk_internal_reflect_ConstructorAccessorImpl = StaticSymbols.putType("Ljdk/internal/reflect/ConstructorAccessorImpl;");

        public static final Symbol<Type> sun_reflect_ConstantPool = StaticSymbols.putType("Lsun/reflect/ConstantPool;");
        public static final Symbol<Type> jdk_internal_reflect_ConstantPool = StaticSymbols.putType("Ljdk/internal/reflect/ConstantPool;");

        public static final Symbol<Type> java_io_Serializable = StaticSymbols.putType(java.io.Serializable.class);
        public static final Symbol<Type> java_nio_ByteBuffer = StaticSymbols.putType(java.nio.ByteBuffer.class);
        public static final Symbol<Type> java_nio_DirectByteBuffer = StaticSymbols.putType("Ljava/nio/DirectByteBuffer;");

        public static final Symbol<Type> java_security_PrivilegedActionException = StaticSymbols.putType(java.security.PrivilegedActionException.class);

        // Shutdown is not public.
        public static final Symbol<Type> java_lang_Shutdown = StaticSymbols.putType("Ljava/lang/Shutdown;");

        public static final Symbol<Type> sun_launcher_LauncherHelper = StaticSymbols.putType(sun.launcher.LauncherHelper.class);

        // Finalizer is not public.
        public static final Symbol<Type> java_lang_ref_Finalizer = StaticSymbols.putType("Ljava/lang/ref/Finalizer;");
        public static final Symbol<Type> java_lang_ref_Reference = StaticSymbols.putType(java.lang.ref.Reference.class);
        public static final Symbol<Type> java_lang_ref_FinalReference = StaticSymbols.putType("Ljava/lang/ref/FinalReference;");
        public static final Symbol<Type> java_lang_ref_WeakReference = StaticSymbols.putType(java.lang.ref.WeakReference.class);
        public static final Symbol<Type> java_lang_ref_SoftReference = StaticSymbols.putType(java.lang.ref.SoftReference.class);
        public static final Symbol<Type> java_lang_ref_PhantomReference = StaticSymbols.putType(java.lang.ref.PhantomReference.class);
        public static final Symbol<Type> java_lang_ref_ReferenceQueue = StaticSymbols.putType(java.lang.ref.ReferenceQueue.class);
        public static final Symbol<Type> java_lang_ref_Reference$Lock = StaticSymbols.putType("Ljava/lang/ref/Reference$Lock;");

        public static final Symbol<Type> sun_misc_Cleaner = StaticSymbols.putType("Lsun/misc/Cleaner;");
        public static final Symbol<Type> jdk_internal_ref_Cleaner = StaticSymbols.putType("Ljdk/internal/ref/Cleaner;");

        public static final Symbol<Type> java_lang_StackTraceElement = StaticSymbols.putType(StackTraceElement.class);
        public static final Symbol<Type> java_lang_StackTraceElement_array = StaticSymbols.putType(StackTraceElement[].class);

        public static final Symbol<Type> java_lang_Error = StaticSymbols.putType(Error.class);
        public static final Symbol<Type> java_lang_NoSuchFieldError = StaticSymbols.putType(NoSuchFieldError.class);
        public static final Symbol<Type> java_lang_NoSuchMethodError = StaticSymbols.putType(NoSuchMethodError.class);
        public static final Symbol<Type> java_lang_IllegalAccessError = StaticSymbols.putType(IllegalAccessError.class);
        public static final Symbol<Type> java_lang_IncompatibleClassChangeError = StaticSymbols.putType(IncompatibleClassChangeError.class);
        public static final Symbol<Type> java_lang_AbstractMethodError = StaticSymbols.putType(AbstractMethodError.class);
        public static final Symbol<Type> java_lang_InternalError = StaticSymbols.putType(InternalError.class);
        public static final Symbol<Type> java_lang_VerifyError = StaticSymbols.putType(VerifyError.class);
        public static final Symbol<Type> java_lang_ClassFormatError = StaticSymbols.putType(ClassFormatError.class);
        public static final Symbol<Type> java_lang_ClassCircularityError = StaticSymbols.putType(ClassCircularityError.class);
        public static final Symbol<Type> java_lang_UnsatisfiedLinkError = StaticSymbols.putType(UnsatisfiedLinkError.class);

        public static final Symbol<Type> java_lang_invoke_MethodType = StaticSymbols.putType(java.lang.invoke.MethodType.class);

        public static final Symbol<Type> java_lang_AssertionStatusDirectives = StaticSymbols.putType("Ljava/lang/AssertionStatusDirectives;");

        public static final Symbol<Type> java_lang_invoke_MethodHandles = StaticSymbols.putType(java.lang.invoke.MethodHandles.class);
        public static final Symbol<Type> java_lang_invoke_VarHandle = StaticSymbols.putType("Ljava/lang/invoke/VarHandle;");
        public static final Symbol<Type> java_lang_invoke_MethodHandles$Lookup = StaticSymbols.putType(java.lang.invoke.MethodHandles.Lookup.class);
        public static final Symbol<Type> java_lang_invoke_CallSite = StaticSymbols.putType(java.lang.invoke.CallSite.class);
        public static final Symbol<Type> java_lang_invoke_DirectMethodHandle = StaticSymbols.putType("Ljava/lang/invoke/DirectMethodHandle;");

        // MethodHandleNatives is not public.
        public static final Symbol<Type> java_lang_invoke_MethodHandleNatives = StaticSymbols.putType("Ljava/lang/invoke/MethodHandleNatives;");
        public static final Symbol<Type> java_lang_invoke_MemberName = StaticSymbols.putType("Ljava/lang/invoke/MemberName;");
        public static final Symbol<Type> java_lang_invoke_MethodHandle = StaticSymbols.putType(java.lang.invoke.MethodHandle.class);
        public static final Symbol<Type> java_lang_invoke_LambdaForm = StaticSymbols.putType("Ljava/lang/invoke/LambdaForm;");
        public static final Symbol<Type> java_lang_invoke_LambdaForm$Compiled = StaticSymbols.putType("Ljava/lang/invoke/LambdaForm$Compiled;");
        public static final Symbol<Type> java_lang_invoke_LambdaForm$Hidden = StaticSymbols.putType("Ljava/lang/invoke/LambdaForm$Hidden;");
        public static final Symbol<Type> sun_reflect_CallerSensitive = StaticSymbols.putType("Lsun/reflect/CallerSensitive;");
        public static final Symbol<Type> jdk_internal_reflect_CallerSensitive = StaticSymbols.putType("Ljdk/internal/reflect/CallerSensitive;");

        // Modules
        public static final Symbol<Type> java_lang_Module = StaticSymbols.putType("Ljava/lang/Module;");

        // Stack walking API
        public static final Symbol<Type> java_lang_StackWalker = StaticSymbols.putType("Ljava/lang/StackWalker;");
        public static final Symbol<Type> java_lang_StackStreamFactory = StaticSymbols.putType("Ljava/lang/StackStreamFactory;");
        public static final Symbol<Type> java_lang_AbstractStackWalker = StaticSymbols.putType("Ljava/lang/StackStreamFactory$AbstractStackWalker;");
        public static final Symbol<Type> java_lang_StackFrameInfo = StaticSymbols.putType("Ljava/lang/StackFrameInfo;");

        // Special threads
        public static final Symbol<Type> java_lang_ref_Finalizer$FinalizerThread = StaticSymbols.putType("Ljava/lang/ref/Finalizer$FinalizerThread;");
        public static final Symbol<Type> java_lang_ref_Reference$ReferenceHandler = StaticSymbols.putType("Ljava/lang/ref/Reference$ReferenceHandler;");
        // java.management
        public static final Symbol<Type> java_lang_management_MemoryManagerMXBean = StaticSymbols.putType(java.lang.management.MemoryManagerMXBean.class);
        public static final Symbol<Type> java_lang_management_MemoryPoolMXBean = StaticSymbols.putType(java.lang.management.MemoryPoolMXBean.class);
        public static final Symbol<Type> java_lang_management_GarbageCollectorMXBean = StaticSymbols.putType(java.lang.management.GarbageCollectorMXBean.class);
        public static final Symbol<Type> sun_management_ManagementFactory = StaticSymbols.putType("Lsun/management/ManagementFactory;");
        public static final Symbol<Type> sun_management_ManagementFactoryHelper = StaticSymbols.putType("Lsun/management/ManagementFactoryHelper;");
        public static final Symbol<Type> java_lang_management_MemoryUsage = StaticSymbols.putType(java.lang.management.MemoryUsage.class);
        public static final Symbol<Type> java_lang_management_ThreadInfo = StaticSymbols.putType(java.lang.management.ThreadInfo.class);

        // Interop conversions.
        public static final Symbol<Type> java_time_Duration = StaticSymbols.putType(Duration.class);
        public static final Symbol<Type> java_time_LocalTime = StaticSymbols.putType(LocalTime.class);
        public static final Symbol<Type> java_time_LocalDateTime = StaticSymbols.putType(LocalDateTime.class);
        public static final Symbol<Type> java_time_LocalDate = StaticSymbols.putType(LocalDate.class);
        public static final Symbol<Type> java_time_Instant = StaticSymbols.putType(Instant.class);
        public static final Symbol<Type> java_time_ZonedDateTime = StaticSymbols.putType(ZonedDateTime.class);
        public static final Symbol<Type> java_util_Date = StaticSymbols.putType(Date.class);
        public static final Symbol<Type> java_time_ZoneId = StaticSymbols.putType(ZoneId.class);

        // Polyglot/interop API.
        public static final Symbol<Type> com_oracle_truffle_espresso_polyglot_Polyglot = StaticSymbols.putType("Lcom/oracle/truffle/espresso/polyglot/Polyglot;");
        public static final Symbol<Type> com_oracle_truffle_espresso_polyglot_ArityException = StaticSymbols.putType("Lcom/oracle/truffle/espresso/polyglot/ArityException;");
        public static final Symbol<Type> com_oracle_truffle_espresso_polyglot_UnknownIdentifierException = StaticSymbols.putType("Lcom/oracle/truffle/espresso/polyglot/UnknownIdentifierException;");
        public static final Symbol<Type> com_oracle_truffle_espresso_polyglot_UnsupportedMessageException = StaticSymbols.putType("Lcom/oracle/truffle/espresso/polyglot/UnsupportedMessageException;");
        public static final Symbol<Type> com_oracle_truffle_espresso_polyglot_UnsupportedTypeException = StaticSymbols.putType("Lcom/oracle/truffle/espresso/polyglot/UnsupportedTypeException;");
        public static final Symbol<Type> com_oracle_truffle_espresso_polyglot_InvalidArrayIndexException = StaticSymbols.putType("Lcom/oracle/truffle/espresso/polyglot/InvalidArrayIndexException;");
        public static final Symbol<Type> com_oracle_truffle_espresso_polyglot_ForeignException = StaticSymbols.putType("Lcom/oracle/truffle/espresso/polyglot/ForeignException;");
        public static final Symbol<Type> com_oracle_truffle_espresso_polyglot_ExceptionType = StaticSymbols.putType("Lcom/oracle/truffle/espresso/polyglot/ExceptionType;");
    }

    /**
     * Contains commonly used (signature) symbols.
     *
     * <p>
     * Naming convention: Use the concatenation of the return type (first) and the parameter types,
     * separated by '_'. Always use unqualified type names.<br>
     * {@link #Object}<br>
     * {@link #_void_String_array}<br>
     * {@link #Thread$State_int}<br>
     * {@link #_void}<br>
     */
    public static final class Signature extends Descriptor {

        public static void ensureInitialized() {
            /* nop */
        }

        public static final Symbol<Signature> _byte = StaticSymbols.putSignature(Type._byte);
        public static final Symbol<Signature> _short = StaticSymbols.putSignature(Type._short);
        public static final Symbol<Signature> _char = StaticSymbols.putSignature(Type._char);
        public static final Symbol<Signature> _int = StaticSymbols.putSignature(Type._int);
        public static final Symbol<Signature> _long = StaticSymbols.putSignature(Type._long);
        public static final Symbol<Signature> _float = StaticSymbols.putSignature(Type._float);
        public static final Symbol<Signature> _double = StaticSymbols.putSignature(Type._double);
        public static final Symbol<Signature> _void = StaticSymbols.putSignature(Type._void);
        public static final Symbol<Signature> _boolean = StaticSymbols.putSignature(Type._boolean);
        public static final Symbol<Signature> Class = StaticSymbols.putSignature(Type.java_lang_Class);
        public static final Symbol<Signature> LocalDate = StaticSymbols.putSignature(Type.java_time_LocalDate);
        public static final Symbol<Signature> LocalTime = StaticSymbols.putSignature(Type.java_time_LocalTime);
        public static final Symbol<Signature> Instant = StaticSymbols.putSignature(Type.java_time_Instant);
        public static final Symbol<Signature> ZoneId = StaticSymbols.putSignature(Type.java_time_ZoneId);
        public static final Symbol<Signature> ZonedDateTime_ZoneId = StaticSymbols.putSignature(Type.java_time_ZonedDateTime, Type.java_time_ZoneId);
        public static final Symbol<Signature> ZoneId_String = StaticSymbols.putSignature(Type.java_time_ZoneId, Type.java_lang_String);

        public static final Symbol<Signature> _void_Object = StaticSymbols.putSignature(Type._void, Type.java_lang_Object);

        public static final Symbol<Signature> Object = StaticSymbols.putSignature(Type.java_lang_Object);
        public static final Symbol<Signature> String = StaticSymbols.putSignature(Type.java_lang_String);
        public static final Symbol<Signature> ClassLoader = StaticSymbols.putSignature(Type.java_lang_ClassLoader);
        public static final Symbol<Signature> Class_PermissionDomain = StaticSymbols.putSignature(Type._void, Type.java_lang_Class, Type.java_security_ProtectionDomain);

        public static final Symbol<Signature> _void_Class = StaticSymbols.putSignature(Type._void, Type.java_lang_Class);
        public static final Symbol<Signature> Class_array = StaticSymbols.putSignature(Type.java_lang_Class_array);

        public static final Symbol<Signature> Object_String_String = StaticSymbols.putSignature(Type.java_lang_Object, Type.java_lang_String, Type.java_lang_String);
        public static final Symbol<Signature> String_String = StaticSymbols.putSignature(Type.java_lang_String, Type.java_lang_String);
        public static final Symbol<Signature> _void_String_array = StaticSymbols.putSignature(Type._void, Type.java_lang_String_array);
        public static final Symbol<Signature> Class_boolean_int_String = StaticSymbols.putSignature(Type.java_lang_Class, Type._boolean, Type._int, Type.java_lang_String);
        public static final Symbol<Signature> Class_String_boolean_ClassLoader = StaticSymbols.putSignature(Type.java_lang_Class, Type.java_lang_String, Type._boolean, Type.java_lang_ClassLoader);

        public static final Symbol<Signature> _void_Throwable = StaticSymbols.putSignature(Type._void, Type.java_lang_Throwable);
        public static final Symbol<Signature> StackTraceElement_array = StaticSymbols.putSignature(Type.java_lang_StackTraceElement_array);
        public static final Symbol<Signature> _void_String_Throwable = StaticSymbols.putSignature(Type._void, Type.java_lang_String, Type.java_lang_Throwable);
        public static final Symbol<Signature> _void_String = StaticSymbols.putSignature(Type._void, Type.java_lang_String);
        public static final Symbol<Signature> Class_String = StaticSymbols.putSignature(Type.java_lang_Class, Type.java_lang_String);
        public static final Symbol<Signature> InputStream_String = StaticSymbols.putSignature(Type.java_io_InputStream, Type.java_lang_String);
        public static final Symbol<Signature> _int_byte_array_int_int = StaticSymbols.putSignature(Type._int, Type._byte_array, Type._int, Type._int);
        public static final Symbol<Signature> ByteBuffer_byte_array = StaticSymbols.putSignature(Type.java_nio_ByteBuffer, Type._byte_array);
        public static final Symbol<Signature> _long_ClassLoader_String = StaticSymbols.putSignature(Type._long, Type.java_lang_ClassLoader, Type.java_lang_String);
        public static final Symbol<Signature> _void_Exception = StaticSymbols.putSignature(Type._void, Type.java_lang_Exception);
        public static final Symbol<Signature> _void_String_String_String_int = StaticSymbols.putSignature(Type._void, Type.java_lang_String, Type.java_lang_String, Type.java_lang_String, Type._int);
        public static final Symbol<Signature> _void_int = StaticSymbols.putSignature(Type._void, Type._int);
        public static final Symbol<Signature> _void_boolean = StaticSymbols.putSignature(Type._void, Type._boolean);
        public static final Symbol<Signature> _void_long = StaticSymbols.putSignature(Type._void, Type._long);
        public static final Symbol<Signature> _void_long_int = StaticSymbols.putSignature(Type._void, Type._long, Type._int);
        public static final Symbol<Signature> _int_int_int = StaticSymbols.putSignature(Type._int, Type._int, Type._int);
        public static final Symbol<Signature> _void_char_array = StaticSymbols.putSignature(Type._void, Type._char_array);
        public static final Symbol<Signature> _char_array = StaticSymbols.putSignature(Type._char_array);
        public static final Symbol<Signature> _int_boolean_boolean = StaticSymbols.putSignature(Type._int, Type._boolean, Type._boolean);
        public static final Symbol<Signature> Object_long_int_int_int_int = StaticSymbols.putSignature(Type.java_lang_Object, Type._long, Type._int, Type._int, Type._int, Type._int);
        public static final Symbol<Signature> Object_long_int_int_int_Object_array = StaticSymbols.putSignature(Type.java_lang_Object, Type._long, Type._int, Type._int, Type._int,
                        Type.java_lang_Object_array);

        public static final Symbol<Signature> Boolean_boolean = StaticSymbols.putSignature(Type.java_lang_Boolean, Type._boolean);
        public static final Symbol<Signature> Byte_byte = StaticSymbols.putSignature(Type.java_lang_Byte, Type._byte);
        public static final Symbol<Signature> Character_char = StaticSymbols.putSignature(Type.java_lang_Character, Type._char);
        public static final Symbol<Signature> Short_short = StaticSymbols.putSignature(Type.java_lang_Short, Type._short);
        public static final Symbol<Signature> Float_float = StaticSymbols.putSignature(Type.java_lang_Float, Type._float);
        public static final Symbol<Signature> Integer_int = StaticSymbols.putSignature(Type.java_lang_Integer, Type._int);
        public static final Symbol<Signature> Double_double = StaticSymbols.putSignature(Type.java_lang_Double, Type._double);
        public static final Symbol<Signature> Long_long = StaticSymbols.putSignature(Type.java_lang_Long, Type._long);

        public static final Symbol<Signature> Object_Object_array = StaticSymbols.putSignature(Type.java_lang_Object, Type.java_lang_Object_array);

        public static final Symbol<Signature> MethodType_Class_Class = StaticSymbols.putSignature(Type.java_lang_invoke_MethodType, Type.java_lang_Class, Type.java_lang_Class_array);
        public static final Symbol<Signature> MethodType_String_ClassLoader = StaticSymbols.putSignature(Type.java_lang_invoke_MethodType, Type.java_lang_String, Type.java_lang_ClassLoader);

        public static final Symbol<Signature> MemberName = StaticSymbols.putSignature(Type.java_lang_invoke_MemberName);

        public static final Symbol<Signature> MemberName_Class_int_Class_String_Object_Object_array = StaticSymbols.putSignature(Type.java_lang_invoke_MemberName, Type.java_lang_Class, Type._int,
                        Type.java_lang_Class, Type.java_lang_String, Type.java_lang_Object, Type.java_lang_Object_array);
        public static final Symbol<Signature> MethodHandle_Class_int_Class_String_Object = StaticSymbols.putSignature(Type.java_lang_invoke_MethodHandle, Type.java_lang_Class, Type._int,
                        Type.java_lang_Class, Type.java_lang_String, Type.java_lang_Object);
        public static final Symbol<Signature> MemberName_Object_Object_Object_Object_Object_Object_array = StaticSymbols.putSignature(
                        Type.java_lang_invoke_MemberName,
                        Type.java_lang_Object,
                        Type.java_lang_Object,
                        Type.java_lang_Object,
                        Type.java_lang_Object,
                        Type.java_lang_Object,
                        Type.java_lang_Object_array);
        public static final Symbol<Signature> MemberName_Object_int_Object_Object_Object_Object_Object_array = StaticSymbols.putSignature(
                        Type.java_lang_invoke_MemberName,
                        Type.java_lang_Object,
                        Type._int,
                        Type.java_lang_Object,
                        Type.java_lang_Object,
                        Type.java_lang_Object,
                        Type.java_lang_Object,
                        Type.java_lang_Object_array);
        public static final Symbol<Signature> Object_Object_int_Object_Object_Object_Object = StaticSymbols.putSignature(
                        Type.java_lang_Object,
                        Type.java_lang_Object,
                        Type._int,
                        Type.java_lang_Object,
                        Type.java_lang_Object,
                        Type.java_lang_Object,
                        Type.java_lang_Object);
        public static final Symbol<Signature> MethodHandles$Lookup = StaticSymbols.putSignature(Type.java_lang_invoke_MethodHandles$Lookup);

        public static final Symbol<Signature> Thread$State_int = StaticSymbols.putSignature(Type.java_lang_Thread$State, Type._int);
        public static final Symbol<Signature> _void_ThreadGroup = StaticSymbols.putSignature(Type._void, Type.java_lang_ThreadGroup);
        public static final Symbol<Signature> _void_ThreadGroup_String = StaticSymbols.putSignature(Type._void, Type.java_lang_ThreadGroup, Type.java_lang_String);
        public static final Symbol<Signature> _void_ThreadGroup_Runnable = StaticSymbols.putSignature(Type._void, Type.java_lang_ThreadGroup, Type.java_lang_Runnable);
        public static final Symbol<Signature> _void_Thread = StaticSymbols.putSignature(Type._void, Type.java_lang_Thread);

        public static final Symbol<Signature> _boolean_Reference = StaticSymbols.putSignature(Type._boolean, Type.java_lang_ref_Reference);

        public static final Symbol<Signature> _void_CodeSource_PermissionCollection = StaticSymbols.putSignature(Type._void, Type.java_security_CodeSource, Type.java_security_PermissionCollection);

        // java.management
        public static final Symbol<Signature> MemoryPoolMXBean_String_boolean_long_long = StaticSymbols.putSignature(Type.java_lang_management_MemoryPoolMXBean, Type.java_lang_String, Type._boolean,
                        Type._long, Type._long);
        public static final Symbol<Signature> MemoryManagerMXBean_String = StaticSymbols.putSignature(Type.java_lang_management_MemoryManagerMXBean, Type.java_lang_String);
        public static final Symbol<Signature> GarbageCollectorMXBean_String_String = StaticSymbols.putSignature(Type.java_lang_management_GarbageCollectorMXBean, Type.java_lang_String,
                        Type.java_lang_String);

        // Polyglot/interop API.
        public static final Symbol<Signature> UnsupportedMessageException = StaticSymbols.putSignature(Type.com_oracle_truffle_espresso_polyglot_UnsupportedMessageException);
        public static final Symbol<Signature> UnsupportedMessageException_Throwable = StaticSymbols.putSignature(Type.com_oracle_truffle_espresso_polyglot_UnsupportedMessageException,
                        Type.java_lang_Throwable);

        public static final Symbol<Signature> UnsupportedTypeException_Object_array_String = StaticSymbols.putSignature(Type.com_oracle_truffle_espresso_polyglot_UnsupportedTypeException,
                        Type.java_lang_Object_array,
                        Type.java_lang_String);
        public static final Symbol<Signature> UnsupportedTypeException_Object_array_String_Throwable = StaticSymbols.putSignature(Type.com_oracle_truffle_espresso_polyglot_UnsupportedTypeException,
                        Type.java_lang_Object_array,
                        Type.java_lang_String,
                        Type.java_lang_Throwable);

        public static final Symbol<Signature> UnknownIdentifierException_String = StaticSymbols.putSignature(Type.com_oracle_truffle_espresso_polyglot_UnknownIdentifierException,
                        Type.java_lang_String);
        public static final Symbol<Signature> UnknownIdentifierException_String_Throwable = StaticSymbols.putSignature(Type.com_oracle_truffle_espresso_polyglot_UnknownIdentifierException,
                        Type.java_lang_String, Type.java_lang_Throwable);

        public static final Symbol<Signature> ArityException_int_int = StaticSymbols.putSignature(Type.com_oracle_truffle_espresso_polyglot_ArityException, Type._int, Type._int);
        public static final Symbol<Signature> ArityException_int_int_Throwable = StaticSymbols.putSignature(Type.com_oracle_truffle_espresso_polyglot_ArityException, Type._int, Type._int,
                        Type.java_lang_Throwable);

        public static final Symbol<Signature> InvalidArrayIndexException_long = StaticSymbols.putSignature(Type.com_oracle_truffle_espresso_polyglot_InvalidArrayIndexException, Type._long);
        public static final Symbol<Signature> InvalidArrayIndexException_long_Throwable = StaticSymbols.putSignature(Type.com_oracle_truffle_espresso_polyglot_InvalidArrayIndexException, Type._long,
                        Type.java_lang_Throwable);
    }
}
