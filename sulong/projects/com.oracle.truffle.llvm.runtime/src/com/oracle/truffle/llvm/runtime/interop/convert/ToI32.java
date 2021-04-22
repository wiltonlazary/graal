/*
 * Copyright (c) 2017, 2021, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.runtime.interop.convert;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.llvm.runtime.except.LLVMPolyglotException;
import com.oracle.truffle.llvm.runtime.library.internal.LLVMAsForeignLibrary;

@GenerateUncached
public abstract class ToI32 extends ForeignToLLVM {

    @Specialization
    protected int fromInt(int value) {
        return value;
    }

    @Specialization
    protected int fromChar(char value) {
        return value;
    }

    @Specialization
    protected int fromShort(short value) {
        return value;
    }

    @Specialization
    protected int fromLong(long value) {
        return (int) value;
    }

    @Specialization
    protected int fromByte(byte value) {
        return value;
    }

    @Specialization
    protected int fromFloat(float value) {
        return (int) value;
    }

    @Specialization
    protected int fromDouble(double value) {
        return (int) value;
    }

    @Specialization
    protected int fromBoolean(boolean value) {
        return value ? 1 : 0;
    }

    @Specialization
    protected int fromString(String value) {
        return getSingleStringCharacter(value);
    }

    @Specialization(limit = "5", guards = {"foreigns.isForeign(obj)", "interop.isNumber(foreigns.asForeign(obj))"})
    protected int fromForeign(Object obj,
                    @CachedLibrary("obj") LLVMAsForeignLibrary foreigns,
                    @CachedLibrary(limit = "3") InteropLibrary interop,
                    @Cached BranchProfile exception) {
        try {
            return interop.asInt(foreigns.asForeign(obj));
        } catch (UnsupportedMessageException ex) {
            exception.enter();
            throw new LLVMPolyglotException(this, "Polyglot number %s cannot be converted to i32", foreigns.asForeign(obj));
        }
    }

    @Fallback
    protected int fromForeignObject(Object obj) {
        throw new LLVMPolyglotException(this, "Polyglot object %s cannot be converted to i32", obj);
    }

    @TruffleBoundary
    static int slowPathPrimitiveConvert(ForeignToLLVM thiz, Object value) throws UnsupportedTypeException {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        } else if (value instanceof Boolean) {
            return ((boolean) value ? 1 : 0);
        } else if (value instanceof Character) {
            return (char) value;
        } else if (value instanceof String) {
            return thiz.getSingleStringCharacter((String) value);
        } else {
            try {
                return InteropLibrary.getFactory().getUncached().asInt(value);
            } catch (UnsupportedMessageException ex) {
                throw UnsupportedTypeException.create(new Object[]{value});
            }
        }
    }
}
