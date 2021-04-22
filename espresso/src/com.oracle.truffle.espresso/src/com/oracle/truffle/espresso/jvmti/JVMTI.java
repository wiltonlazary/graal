/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.truffle.espresso.jvmti;

import java.util.ArrayList;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.espresso.ffi.NativeSignature;
import com.oracle.truffle.espresso.ffi.NativeType;
import com.oracle.truffle.espresso.ffi.Pointer;
import com.oracle.truffle.espresso.ffi.RawPointer;
import com.oracle.truffle.espresso.jni.Callback;
import com.oracle.truffle.espresso.jvmti.structs.Structs;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.perf.DebugCloseable;
import com.oracle.truffle.espresso.perf.DebugTimer;
import com.oracle.truffle.espresso.runtime.EspressoContext;

public final class JVMTI {
    private static final DebugTimer STRUCTS_TIMER = DebugTimer.create("native struct creation");

    private final EspressoContext context;
    private @Pointer TruffleObject initializeJvmtiHandlerContext;
    private @Pointer TruffleObject lookupMemberOffset;
    private final @Pointer TruffleObject initializeJvmtiContext;
    private final @Pointer TruffleObject disposeJvmtiContext;

    @CompilationFinal //
    private volatile Structs structs;

    private final ArrayList<JVMTIEnv> activeEnvironments = new ArrayList<>();

    private JvmtiPhase phase;

    public JVMTI(EspressoContext context, TruffleObject mokapotLibrary) {
        this.context = context;

        this.initializeJvmtiHandlerContext = context.getNativeAccess().lookupAndBindSymbol(mokapotLibrary,
                        "initializeJvmtiHandlerContext",
                        NativeSignature.create(NativeType.VOID, NativeType.POINTER));
        this.lookupMemberOffset = context.getNativeAccess().lookupAndBindSymbol(mokapotLibrary,
                        "lookupMemberOffset",
                        NativeSignature.create(NativeType.LONG, NativeType.POINTER, NativeType.POINTER));

        // Pre-emptively initializes the structs until actually used, to make sure everything keeps
        // working.
        getStructs();

        this.initializeJvmtiContext = context.getNativeAccess().lookupAndBindSymbol(mokapotLibrary,
                        "initializeJvmtiContext",
                        NativeSignature.create(NativeType.POINTER, NativeType.POINTER, NativeType.INT));
        this.disposeJvmtiContext = context.getNativeAccess().lookupAndBindSymbol(mokapotLibrary,
                        "disposeJvmtiContext",
                        NativeSignature.create(NativeType.VOID, NativeType.POINTER, NativeType.INT, NativeType.POINTER));
    }

    public static boolean isJvmtiVersion(int version) {
        return JvmtiVersion.isJvmtiVersion(version);
    }

    private static boolean isSupportedJvmtiVersion(int version) {
        return JvmtiVersion.isSupportedJvmtiVersion(version);
    }

    private Structs initializeStructs() {
        Structs[] box = new Structs[1];
        Callback doInitStructs = new Callback(1, new Callback.Function() {
            @Override
            @TruffleBoundary
            public Object call(Object... args) {
                TruffleObject memberInfoPtr = (TruffleObject) args[0];
                box[0] = new Structs(context.getJNI(), memberInfoPtr, lookupMemberOffset);
                return RawPointer.nullInstance();
            }
        });
        /*
         * Go down to native to initialize the data structure storing the offsets of used structs
         * (The memberInfoPtr seen in the callback). This will get back to java code once the data
         * structure is created. Once we get out of the native call, the structure is freed and
         * cannot be used anymore.
         */
        @Pointer
        TruffleObject closure = context.getNativeAccess().createNativeClosure(doInitStructs, NativeSignature.create(NativeType.VOID, NativeType.POINTER));
        try {
            InteropLibrary.getUncached().execute(initializeJvmtiHandlerContext, closure);
        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere();
        }

        // Remove references to symbols so they can be collected.
        initializeJvmtiHandlerContext = null;
        lookupMemberOffset = null;
        return box[0];
    }

    @TruffleBoundary
    public synchronized TruffleObject create(int version) {
        if (!isSupportedJvmtiVersion(version)) {
            return null;
        }
        JVMTIEnv jvmtiEnv = new JVMTIEnv(context, initializeJvmtiContext, version);
        activeEnvironments.add(jvmtiEnv);
        return jvmtiEnv.getEnv();
    }

    public synchronized void dispose() {
        for (JVMTIEnv jvmtiEnv : activeEnvironments) {
            jvmtiEnv.dispose(disposeJvmtiContext);
        }
        activeEnvironments.clear();
    }

    @TruffleBoundary
    synchronized void dispose(JVMTIEnv env) {
        CompilerAsserts.neverPartOfCompilation();
        if (activeEnvironments.contains(env)) {
            env.dispose(disposeJvmtiContext);
            activeEnvironments.remove(env);
        }
    }

    @SuppressWarnings("try")
    public Structs getStructs() {
        if (structs == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            synchronized (this) {
                // All fields in structs are final. Can double-check lock without volatile.
                if (structs == null) {
                    try (DebugCloseable timer = STRUCTS_TIMER.scope(context.getTimers())) {
                        structs = initializeStructs();
                    }
                }
            }
        }
        return structs;
    }

    public synchronized int getPhase() {
        return phase.value();
    }

    public synchronized void enterPhase(JvmtiPhase jvmtiPhase) {
        this.phase = jvmtiPhase;
    }

    public synchronized void postVmStart() {
        enterPhase(JvmtiPhase.START);
    }

    public synchronized void postVmInit() {
        enterPhase(JvmtiPhase.LIVE);
    }

    public synchronized void postVmDeath() {
        enterPhase(JvmtiPhase.DEAD);
    }

}
