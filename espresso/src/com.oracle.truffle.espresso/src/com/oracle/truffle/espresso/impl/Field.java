/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.espresso.impl;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.espresso.classfile.Constants;
import com.oracle.truffle.espresso.classfile.attributes.SignatureAttribute;
import com.oracle.truffle.espresso.descriptors.Symbol;
import com.oracle.truffle.espresso.descriptors.Symbol.ModifiedUTF8;
import com.oracle.truffle.espresso.descriptors.Symbol.Name;
import com.oracle.truffle.espresso.descriptors.Symbol.Type;
import com.oracle.truffle.espresso.jdwp.api.FieldBreakpoint;
import com.oracle.truffle.espresso.jdwp.api.FieldRef;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.JavaKind;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.runtime.Attribute;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.espresso.vm.InterpreterToVM;

/**
 * Represents a resolved Espresso field.
 */
public final class Field extends Member<Type> implements FieldRef {

    public static final Field[] EMPTY_ARRAY = new Field[0];

    private final LinkedField linkedField;
    private final ObjectKlass holder;
    private volatile Klass typeKlassCache;

    @CompilationFinal private Symbol<ModifiedUTF8> genericSignature = null;

    public Field(ObjectKlass holder, LinkedField linkedField, boolean hidden) {
        super(hidden ? null : linkedField.getType(), linkedField.getName());
        this.linkedField = linkedField;
        this.holder = holder;
    }

    public Symbol<Type> getType() {
        return descriptor;
    }

    public Attribute[] getAttributes() {
        return linkedField.getParserField().getAttributes();
    }

    public Symbol<ModifiedUTF8> getGenericSignature() {
        if (genericSignature == null) {
            SignatureAttribute attr = (SignatureAttribute) linkedField.getAttribute(SignatureAttribute.NAME);
            if (attr == null) {
                genericSignature = ModifiedUTF8.fromSymbol(getType());
            } else {
                genericSignature = holder.getConstantPool().symbolAt(attr.getSignatureIndex());
            }
        }
        return genericSignature;
    }

    public boolean isHidden() {
        return getDescriptor() == null;
    }

    public JavaKind getKind() {
        return linkedField.getKind();
    }

    public int getModifiers() {
        return linkedField.getFlags() & Constants.JVM_RECOGNIZED_FIELD_MODIFIERS;
    }

    @Override
    public ObjectKlass getDeclaringKlass() {
        return holder;
    }

    /**
     * The slot serves as the position in the `field table` of the ObjectKlass.
     */
    public int getSlot() {
        return linkedField.getSlot();
    }

    /**
     * The index is the actual offset in the field array of an actual instance.
     */
    public int getIndex() {
        return linkedField.getIndex();
    }

    @Override
    public String toString() {
        return "EspressoField<" + getDeclaringKlass() + "." + getName() + ":" + getType() + ">";
    }

    public Object get(StaticObject self) {
        assert getDeclaringKlass().isAssignableFrom(self.getKlass());
        // @formatter:off
        switch (getKind()) {
            case Boolean : return InterpreterToVM.getFieldBoolean(self, this);
            case Byte    : return InterpreterToVM.getFieldByte(self, this);
            case Short   : return InterpreterToVM.getFieldShort(self, this);
            case Char    : return InterpreterToVM.getFieldChar(self, this);
            case Int     : return InterpreterToVM.getFieldInt(self, this);
            case Float   : return InterpreterToVM.getFieldFloat(self, this);
            case Long    : return InterpreterToVM.getFieldLong(self, this);
            case Double  : return InterpreterToVM.getFieldDouble(self, this);
            case Object  : return InterpreterToVM.getFieldObject(self, this);
            default      : throw EspressoError.shouldNotReachHere();
        }
        // @formatter:on
    }

    public void set(StaticObject self, Object value) {
        assert value != null;
        assert getDeclaringKlass().isAssignableFrom(self.getKlass());
        // @formatter:off
        switch (getKind()) {
            case Boolean : InterpreterToVM.setFieldBoolean((boolean) value, self, this); break;
            case Byte    : InterpreterToVM.setFieldByte((byte) value, self, this);       break;
            case Short   : InterpreterToVM.setFieldShort((short) value, self, this);     break;
            case Char    : InterpreterToVM.setFieldChar((char) value, self, this);       break;
            case Int     : InterpreterToVM.setFieldInt((int) value, self, this);         break;
            case Float   : InterpreterToVM.setFieldFloat((float) value, self, this);     break;
            case Long    : InterpreterToVM.setFieldLong((long) value, self, this);       break;
            case Double  : InterpreterToVM.setFieldDouble((double) value, self, this);   break;
            case Object  : InterpreterToVM.setFieldObject((StaticObject) value, self, this); break;
            default      : throw EspressoError.shouldNotReachHere();
        }
        // @formatter:on
    }

    public Klass resolveTypeKlass() {
        Klass tk = typeKlassCache;
        if (tk == null) {
            synchronized (this) {
                tk = typeKlassCache;
                if (tk == null) {
                    tk = getDeclaringKlass().getMeta().resolveSymbolOrFail(getType(),
                                    getDeclaringKlass().getDefiningClassLoader(),
                                    getDeclaringKlass().protectionDomain());
                    typeKlassCache = tk;
                }
            }
        }
        return typeKlassCache;
    }

    public Attribute getAttribute(Symbol<Name> attrName) {
        return linkedField.getAttribute(attrName);
    }

    public static Field getReflectiveFieldRoot(StaticObject seed, Meta meta) {
        StaticObject curField = seed;
        Field target = null;
        while (target == null) {
            target = (Field) curField.getHiddenField(meta.HIDDEN_FIELD_KEY);
            if (target == null) {
                curField = (StaticObject) meta.java_lang_reflect_Field_root.get(curField);
            }
        }
        return target;
    }

    public StaticObject getAndSetObject(StaticObject self, StaticObject value) {
        return self.getAndSetObject(this, value);
    }

    public void checkLoadingConstraints(StaticObject loader1, StaticObject loader2) {
        getDeclaringKlass().getContext().getRegistries().checkLoadingConstraint(getType(), loader1, loader2);
    }

    // region jdwp-specific
    @Override
    public byte getTagConstant() {
        return getKind().toTagConstant();
    }

    @Override
    public String getNameAsString() {
        return super.getName().toString();
    }

    @Override
    public String getTypeAsString() {
        return super.getDescriptor().toString();
    }

    @Override
    public String getGenericSignatureAsString() {
        Symbol<ModifiedUTF8> signature = getGenericSignature();
        return signature.toString();
    }

    @Override
    public Object getValue(Object self) {
        return get((StaticObject) self);
    }

    @Override
    public void setValue(Object self, Object value) {
        set((StaticObject) self, value);
    }

    private final StableBoolean hasActiveBreakpoints = new StableBoolean(false);

    // array with maximum size 2, one access info and/or one modification info.
    private FieldBreakpoint[] infos = null;

    @Override
    public boolean hasActiveBreakpoint() {
        return hasActiveBreakpoints.get();
    }

    @Override
    public FieldBreakpoint[] getFieldBreakpointInfos() {
        return infos;
    }

    @Override
    public void addFieldBreakpointInfo(FieldBreakpoint info) {
        if (infos == null) {
            infos = new FieldBreakpoint[]{info};
            hasActiveBreakpoints.set(true);
            return;
        }

        int length = infos.length;
        FieldBreakpoint[] temp = new FieldBreakpoint[length + 1];
        System.arraycopy(infos, 0, temp, 0, length);
        temp[length] = info;
        infos = temp;
        hasActiveBreakpoints.set(true);
    }

    @Override
    public void removeFieldBreakpointInfo(int requestId) {
        // shrink the array to avoid null values
        switch (infos.length) {
            case 0:
                throw new RuntimeException("Field: " + getNameAsString() + " should contain field breakpoint info");
            case 1:
                infos = null;
                hasActiveBreakpoints.set(false);
                return;
            case 2:
                FieldBreakpoint[] temp = new FieldBreakpoint[1];
                FieldBreakpoint info = infos[0];
                if (info.getRequestId() == requestId) {
                    // remove index 0, but keep info at index 1
                    temp[0] = infos[1];
                    infos = temp;
                    return;
                }
                info = infos[1];
                if (info.getRequestId() == requestId) {
                    // remove index 1, but keep info at index 0
                    temp[0] = infos[0];
                    infos = temp;
                    return;
                }
        }
    }

    /**
     * Helper class that uses an assumption to switch between two "stable" states efficiently.
     * Copied from DebuggerSession with modifications to the set method to make it thread safe (but
     * slower on the slow path).
     */
    static final class StableBoolean {

        @CompilerDirectives.CompilationFinal private volatile Assumption unchanged;
        @CompilerDirectives.CompilationFinal private volatile boolean value;

        StableBoolean(boolean initialValue) {
            this.value = initialValue;
            this.unchanged = Truffle.getRuntime().createAssumption("Unchanged boolean");
        }

        @SuppressFBWarnings(value = "UG_SYNC_SET_UNSYNC_GET", justification = "The get method returns a volatile field.")
        public boolean get() {
            if (unchanged.isValid()) {
                return value;
            } else {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                return value;
            }
        }

        /**
         * This method needs to be behind a boundary due to the fact that compiled code will
         * constant fold the value, hence the first check might yield a wrong result.
         * 
         * @param value
         */
        @CompilerDirectives.TruffleBoundary
        public synchronized void set(boolean value) {
            if (this.value != value) {
                this.value = value;
                Assumption old = this.unchanged;
                unchanged = Truffle.getRuntime().createAssumption("Unchanged boolean");
                old.invalidate();
            }
        }
    }

    // endregion jdwp-specific
}
