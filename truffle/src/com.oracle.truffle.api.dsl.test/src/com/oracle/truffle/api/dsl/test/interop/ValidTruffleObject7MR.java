/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.truffle.api.dsl.test.interop;

import com.oracle.truffle.api.dsl.test.ExpectError;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.MessageResolution;
import com.oracle.truffle.api.interop.Resolve;
import com.oracle.truffle.api.nodes.Node;

@SuppressWarnings("unused")
@MessageResolution(receiverType = ValidTruffleObject7.class)
public class ValidTruffleObject7MR {
    @Resolve(message = "EXECUTE")
    public abstract static class Execute2 extends Node {

        @ExpectError({"Wrong number of arguments. Expected signature: ([frame: VirtualFrame], receiverObject: TruffleObject, arguments: Object[])"})
        public Object access(String string, ValidTruffleObject0 object, Object[] args) {
            return true;
        }
    }

    @Resolve(message = "INVOKE")
    public abstract static class Invoke2 extends Node {

        @ExpectError({"The 3 argument must be a java.lang.String- but is int"})
        public Object access(VirtualFrame frame, ValidTruffleObject0 object, int name, Object[] args) {
            return true;
        }
    }

    @Resolve(message = "IS_BOXED")
    public abstract static class IsBoxed2 extends Node {

        @ExpectError({"Wrong number of arguments. Expected signature: ([frame: VirtualFrame], receiverObject: TruffleObject)"})
        public Object access(String string, ValidTruffleObject0 object) {
            return true;
        }
    }

    @ExpectError({"Class must be abstract"})
    @Resolve(message = "READ")
    public static class ReadNode3 extends Node {
    }

    @ExpectError({"Class must be abstract"})
    @Resolve(message = "READ")
    public final class ReadNodeNonStatic extends Node {
    }

    @Resolve(message = "WRITE")
    public abstract static class WriteNode2 extends Node {

        @ExpectError({"Wrong number of arguments. Expected signature: ([frame: VirtualFrame], receiverObject: TruffleObject, identifier: String, value: Object)"})
        protected int access(VirtualFrame frame, Object receiver, Object name) {
            return 0;
        }
    }

}
