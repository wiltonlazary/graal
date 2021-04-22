/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.espresso.nodes;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.StaticObject;

/**
 * Node that simulates espresso statements for debugging support.
 */
public final class EspressoStatementNode extends EspressoInstrumentableNode {

    private final int startBci;
    private final int lineNumber;

    EspressoStatementNode(int startBci, int lineNumber) {
        this.lineNumber = lineNumber;
        this.startBci = startBci;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return StaticObject.NULL;
    }

    @Override
    public SourceSection getSourceSection() {
        Source s = getBytecodesNode().getSource();
        if (s != null) {
            return s.createSection(lineNumber);
        } else {
            // TODO should this really happen? If there is a line number table
            // shouldn't there also be a source file?
            return null;
        }
    }

    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StandardTags.StatementTag.class;
    }

    public BytecodeNode getBytecodesNode() {
        // parent is normally the BytecodeNode.InstrumentationSupport
        // parents parent is normally the BytecodeNode
        Node parent = getParent();

        while (parent instanceof WrapperNode || parent instanceof BytecodeNode.InstrumentationSupport) {
            parent = parent.getParent();
        }
        assert !(parent instanceof WrapperNode);
        return (BytecodeNode) parent;
    }

    @Override
    public int getCurrentBCI(@SuppressWarnings("unused") Frame frame) {
        return startBci;
    }

    @Override
    public Method getMethod() {
        return getBytecodesNode().getMethod();
    }

    @Override
    public EspressoContext getContext() {
        return getBytecodesNode().getContext();
    }
}
