/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.espresso;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.graalvm.home.Version;
import org.graalvm.options.OptionDescriptors;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.Registration;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.espresso.classfile.attributes.Local;
import com.oracle.truffle.espresso.classfile.constantpool.Utf8Constant;
import com.oracle.truffle.espresso.descriptors.ByteSequence;
import com.oracle.truffle.espresso.descriptors.Names;
import com.oracle.truffle.espresso.descriptors.Signatures;
import com.oracle.truffle.espresso.descriptors.StaticSymbols;
import com.oracle.truffle.espresso.descriptors.Symbol.Name;
import com.oracle.truffle.espresso.descriptors.Symbol.Signature;
import com.oracle.truffle.espresso.descriptors.Symbol.Type;
import com.oracle.truffle.espresso.descriptors.Symbols;
import com.oracle.truffle.espresso.descriptors.Types;
import com.oracle.truffle.espresso.descriptors.Utf8ConstantTable;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.nodes.BytecodeNode;
import com.oracle.truffle.espresso.nodes.EspressoRootNode;
import com.oracle.truffle.espresso.nodes.EspressoStatementNode;
import com.oracle.truffle.espresso.nodes.interop.DestroyVMNode;
import com.oracle.truffle.espresso.nodes.interop.ExitCodeNode;
import com.oracle.truffle.espresso.nodes.quick.QuickNode;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.EspressoExitException;
import com.oracle.truffle.espresso.substitutions.Substitutions;

@ProvidedTags({StandardTags.RootTag.class, StandardTags.RootBodyTag.class, StandardTags.StatementTag.class})
@Registration(id = EspressoLanguage.ID, name = EspressoLanguage.NAME, version = EspressoLanguage.VERSION, contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE)
public final class EspressoLanguage extends TruffleLanguage<EspressoContext> {

    public static final String ID = "java";
    public static final String NAME = "Java";
    public static final String VERSION = "1.8|11";

    // Espresso VM info
    public static final String VM_SPECIFICATION_NAME = "Java Virtual Machine Specification";
    public static final String VM_SPECIFICATION_VENDOR = "Oracle Corporation";
    public static final String VM_VERSION = /* 1.8|11 */ "espresso-" + Version.getCurrent();
    public static final String VM_VENDOR = "Oracle Corporation";
    public static final String VM_NAME = "Espresso 64-Bit VM";
    public static final String VM_INFO = "mixed mode";

    public static final String FILE_EXTENSION = ".class";

    private static final String SCOPE_NAME = "block";

    private final Utf8ConstantTable utf8Constants;
    private final Names names;
    private final Types types;
    private final Signatures signatures;

    private long startupClockNanos = 0;

    public EspressoLanguage() {
        // Initialize statically defined symbols and substitutions.
        Name.ensureInitialized();
        Type.ensureInitialized();
        Signature.ensureInitialized();
        Substitutions.ensureInitialized();

        // Raw symbols are not exposed directly, use the typed interfaces: Names, Types and
        // Signatures instead.
        Symbols symbols = new Symbols(StaticSymbols.freeze());
        this.utf8Constants = new Utf8ConstantTable(symbols);
        this.names = new Names(symbols);
        this.types = new Types(symbols);
        this.signatures = new Signatures(symbols, types);
    }

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        return new EspressoOptionsOptionDescriptors();
    }

    @Override
    protected EspressoContext createContext(final TruffleLanguage.Env env) {
        // TODO(peterssen): Redirect in/out to env.in()/out()
        EspressoContext context = new EspressoContext(env, this);
        context.setMainArguments(env.getApplicationArguments());
        return context;
    }

    // Remove in GR-26337
    @SuppressWarnings("deprecation")
    @Override
    protected Iterable<com.oracle.truffle.api.Scope> findLocalScopes(EspressoContext context, Node node, Frame frame) {
        int currentBci;

        Node espressoNode = findKnownEspressoNode(node);

        Method method;
        Node scopeNode;
        if (espressoNode instanceof QuickNode) {
            QuickNode quick = (QuickNode) espressoNode;
            currentBci = quick.getBCI();
            method = quick.getBytecodesNode().getMethod();
            scopeNode = quick.getBytecodesNode();
        } else if (espressoNode instanceof EspressoStatementNode) {
            EspressoStatementNode statementNode = (EspressoStatementNode) espressoNode;
            currentBci = statementNode.getBci();
            method = statementNode.getBytecodesNode().getMethod();
            scopeNode = statementNode.getBytecodesNode();
        } else if (espressoNode instanceof BytecodeNode) {
            BytecodeNode bytecodeNode = (BytecodeNode) espressoNode;
            try {
                currentBci = bytecodeNode.readBCI(frame);
            } catch (Throwable t) {
                // fall back to entry of method then
                currentBci = 0;
            }
            method = bytecodeNode.getMethod();
            scopeNode = bytecodeNode;
        } else {
            return super.findLocalScopes(context, espressoNode, frame);
        }
        // construct the current scope with valid local variables information
        Local[] liveLocals = method.getLocalVariableTable().getLocalsAt(currentBci);
        if (liveLocals.length == 0) {
            // class was compiled without a local variable table
            // include "this" in method arguments throughout the method
            int localCount = !method.isStatic() ? 1 : 0;
            localCount += method.getParameterCount();
            liveLocals = new Local[localCount];
            Klass[] parameters = (Klass[]) method.getParameters();
            if (!method.isStatic()) {
                // include 'this' and method arguments
                liveLocals[0] = new Local(utf8Constants.getOrCreate(Name.thiz), utf8Constants.getOrCreate(method.getDeclaringKlass().getType()), 0, 65536, 0);
                for (int i = 1; i < localCount; i++) {
                    Klass param = parameters[i - 1];
                    Utf8Constant name = utf8Constants.getOrCreate(ByteSequence.create("" + (i - 1)));
                    Utf8Constant type = utf8Constants.getOrCreate(param.getType());
                    liveLocals[i] = new Local(name, type, 0, 65536, i);
                }
            } else {
                // only include method arguments
                for (int i = 0; i < localCount; i++) {
                    Klass param = parameters[i];
                    liveLocals[i] = new Local(utf8Constants.getOrCreate(ByteSequence.create("" + (i - 1))), utf8Constants.getOrCreate(param.getType()), 0, 65536, i);
                }
            }
        }
        com.oracle.truffle.api.Scope scope = com.oracle.truffle.api.Scope.newBuilder(SCOPE_NAME, EspressoScope.createVariables(liveLocals, frame)).node(scopeNode).build();
        return Collections.singletonList(scope);
    }

    private static Node findKnownEspressoNode(Node input) {
        Node currentNode = input;
        boolean known = false;
        while (currentNode != null && !known) {
            if (currentNode instanceof QuickNode || currentNode instanceof BytecodeNode || currentNode instanceof EspressoStatementNode) {
                known = true;
            } else if (currentNode instanceof EspressoRootNode) {
                EspressoRootNode rootNode = (EspressoRootNode) currentNode;
                if (rootNode.isBytecodeNode()) {
                    return rootNode.getBytecodeNode();
                }
            } else {
                currentNode = currentNode.getParent();
            }
        }
        return currentNode;
    }

    @Override
    protected void initializeContext(final EspressoContext context) throws Exception {
        startupClockNanos = System.nanoTime();
        context.initializeContext();
    }

    @Override
    protected void finalizeContext(EspressoContext context) {
        long elapsedTimeNanos = System.nanoTime() - startupClockNanos;
        long seconds = TimeUnit.NANOSECONDS.toSeconds(elapsedTimeNanos);
        if (seconds > 10) {
            context.getLogger().log(Level.FINE, "Time spent in Espresso: {0} s", seconds);
        } else {
            context.getLogger().log(Level.FINE, "Time spent in Espresso: {0} ms", TimeUnit.NANOSECONDS.toMillis(elapsedTimeNanos));
        }

        context.prepareDispose();
        try {
            context.doExit(0);
        } catch (EspressoExitException e) {
            // Expected. Suppress. We do not want to throw during context closing.
        }
    }

    @Override
    protected Object getScope(EspressoContext context) {
        return context.getBindings();
    }

    @Override
    protected void disposeContext(final EspressoContext context) {
        context.disposeContext();
    }

    @Override
    protected CallTarget parse(final ParsingRequest request) throws Exception {
        final EspressoContext context = getCurrentContext();
        assert context.isInitialized();
        String contents = request.getSource().getCharacters().toString();
        if (DestroyVMNode.EVAL_NAME.equals(contents)) {
            RootNode node = new DestroyVMNode(this);
            return Truffle.getRuntime().createCallTarget(node);
        }
        if (ExitCodeNode.EVAL_NAME.equals(contents)) {
            RootNode node = new ExitCodeNode(this);
            return Truffle.getRuntime().createCallTarget(node);
        }
        throw new UnsupportedOperationException("Unsupported operation. Use the language bindings to load classes e.g. context.getBindings(\"" + ID + "\").getMember(\"java.lang.Integer\")");
    }

    public Utf8ConstantTable getUtf8ConstantTable() {
        return utf8Constants;
    }

    public Names getNames() {
        return names;
    }

    public Types getTypes() {
        return types;
    }

    public Signatures getSignatures() {
        return signatures;
    }

    public static EspressoContext getCurrentContext() {
        return getCurrentContext(EspressoLanguage.class);
    }

    public String getEspressoHome() {
        return getLanguageHome();
    }

    @Override
    protected boolean isThreadAccessAllowed(Thread thread,
                    boolean singleThreaded) {
        // allow access from any thread instead of just one
        return true;
    }

    @Override
    protected void initializeMultiThreading(EspressoContext context) {
        // perform actions when the context is switched to multi-threading
        // context.singleThreaded.invalidate();
    }

    @Override
    protected void initializeThread(EspressoContext context, Thread thread) {
        context.createThread(thread);
    }

    @Override
    protected void disposeThread(EspressoContext context, Thread thread) {
        context.disposeThread(thread);
    }

}
