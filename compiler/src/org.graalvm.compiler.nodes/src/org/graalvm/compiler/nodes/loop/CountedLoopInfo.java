/*
 * Copyright (c) 2012, 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.nodes.loop;

import static java.lang.Math.abs;
import static org.graalvm.compiler.nodes.calc.BinaryArithmeticNode.add;
import static org.graalvm.compiler.nodes.calc.BinaryArithmeticNode.sub;
import static org.graalvm.compiler.nodes.loop.MathUtil.unsignedDivBefore;

import org.graalvm.compiler.core.common.type.IntegerStamp;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.util.UnsignedLong;
import org.graalvm.compiler.debug.DebugCloseable;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.GuardNode;
import org.graalvm.compiler.nodes.IfNode;
import org.graalvm.compiler.nodes.LogicNode;
import org.graalvm.compiler.nodes.LoopBeginNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.ConditionalNode;
import org.graalvm.compiler.nodes.calc.NegateNode;
import org.graalvm.compiler.nodes.extended.GuardingNode;
import org.graalvm.compiler.nodes.loop.InductionVariable.Direction;
import org.graalvm.compiler.nodes.util.IntegerHelper;
import org.graalvm.compiler.nodes.util.SignedIntegerHelper;
import org.graalvm.compiler.nodes.util.UnsignedIntegerHelper;

import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.SpeculationLog;

public class CountedLoopInfo {

    protected final LoopEx loop;
    protected InductionVariable iv;
    protected ValueNode end;
    protected boolean oneOff;
    protected AbstractBeginNode body;
    protected IfNode ifNode;
    protected final boolean unsigned;

    protected CountedLoopInfo(LoopEx loop, InductionVariable iv, IfNode ifNode, ValueNode end, boolean oneOff, AbstractBeginNode body, boolean unsigned) {
        assert iv.direction() != null;
        this.loop = loop;
        this.iv = iv;
        this.end = end;
        this.oneOff = oneOff;
        this.body = body;
        this.ifNode = ifNode;
        this.unsigned = unsigned;
    }

    /**
     * Returns a node that computes the maximum trip count of this loop. That is the trip count of
     * this loop assuming it is not exited by an other exit than the {@linkplain #getLimitTest()
     * count check}.
     *
     * This count is exact if {@link #isExactTripCount()} returns true.
     *
     * THIS VALUE SHOULD BE TREATED AS UNSIGNED.
     */
    public ValueNode maxTripCountNode() {
        return maxTripCountNode(false);
    }

    public boolean isUnsignedCheck() {
        return this.unsigned;
    }

    public ValueNode maxTripCountNode(boolean assumeLoopEntered) {
        return maxTripCountNode(assumeLoopEntered, getCounterIntegerHelper());
    }

    /**
     * Returns a node that computes the maximum trip count of this loop. That is the trip count of
     * this loop assuming it is not exited by an other exit than the {@linkplain #getLimitTest()
     * count check}.
     *
     * This count is exact if {@link #isExactTripCount()} returns true.
     *
     * THIS VALUE SHOULD BE TREATED AS UNSIGNED.
     *
     * @param assumeLoopEntered if true the check that the loop is entered at all will be omitted.
     */
    protected ValueNode maxTripCountNode(boolean assumeLoopEntered, IntegerHelper integerHelper) {
        StructuredGraph graph = iv.valueNode().graph();
        Stamp stamp = iv.valueNode().stamp(NodeView.DEFAULT);

        ValueNode max;
        ValueNode min;
        ValueNode absStride;
        if (iv.direction() == Direction.Up) {
            absStride = iv.strideNode();
            max = end;
            min = iv.initNode();
        } else {
            assert iv.direction() == Direction.Down;
            absStride = NegateNode.create(iv.strideNode(), NodeView.DEFAULT);
            max = iv.initNode();
            min = end;
        }
        ValueNode range = sub(max, min);

        ConstantNode one = ConstantNode.forIntegerStamp(stamp, 1, graph);
        if (oneOff) {
            range = add(range, one);
        }
        // round-away-from-zero divison: (range + stride -/+ 1) / stride
        ValueNode denominator = add(graph, range, sub(absStride, one), NodeView.DEFAULT);
        ValueNode div = unsignedDivBefore(graph, loop.entryPoint(), denominator, absStride, null);

        if (assumeLoopEntered) {
            return graph.addOrUniqueWithInputs(div);
        }
        ConstantNode zero = ConstantNode.forIntegerStamp(stamp, 0, graph);
        // This check is "wide": it looks like min <= max
        // That's OK even if the loop is strict (`!isLimitIncluded()`)
        // because in this case, `div` will be zero when min == max
        LogicNode noEntryCheck = integerHelper.createCompareNode(max, min, NodeView.DEFAULT);
        return graph.addOrUniqueWithInputs(ConditionalNode.create(noEntryCheck, zero, div, NodeView.DEFAULT));
    }

    /**
     * Determine if the loop might be entered. Returns {@code false} if we can tell statically that
     * the loop cannot be entered; returns {@code true} if the loop might possibly be entered,
     * including in the case where we cannot be sure statically.
     *
     * @return false if the loop can definitely not be entered, true otherwise
     */
    public boolean loopMightBeEntered() {
        Stamp stamp = iv.valueNode().stamp(NodeView.DEFAULT);

        ValueNode max;
        ValueNode min;
        if (iv.direction() == Direction.Up) {
            max = end;
            min = iv.initNode();
        } else {
            assert iv.direction() == Direction.Down;
            max = iv.initNode();
            min = end;
        }
        if (oneOff) {
            // Ensure the constant is value numbered in the graph. Don't add other nodes to the
            // graph, they will be dead code.
            StructuredGraph graph = iv.valueNode().graph();
            max = add(max, ConstantNode.forIntegerStamp(stamp, 1, graph), NodeView.DEFAULT);
        }

        LogicNode entryCheck = getCounterIntegerHelper().createCompareNode(min, max, NodeView.DEFAULT);
        if (entryCheck.isContradiction()) {
            // We can definitely not enter this loop.
            return false;
        } else {
            // We don't know for sure that the loop can't be entered, so assume it can.
            return true;
        }
    }

    /**
     * @return true if the loop has constant bounds.
     */
    public boolean isConstantMaxTripCount() {
        return end instanceof ConstantNode && iv.isConstantInit() && iv.isConstantStride();
    }

    public UnsignedLong constantMaxTripCount() {
        assert isConstantMaxTripCount();
        return new UnsignedLong(rawConstantMaxTripCount());
    }

    /**
     * Compute the raw value of the trip count for this loop. THIS IS AN UNSIGNED VALUE;
     */
    private long rawConstantMaxTripCount() {
        assert iv.direction() != null;
        long endValue = end.asJavaConstant().asLong();
        long initValue = iv.constantInit();
        long range;
        long absStride;
        IntegerHelper helper = getCounterIntegerHelper(64);
        if (iv.direction() == Direction.Up) {
            if (helper.compare(endValue, initValue) < 0) {
                return 0;
            }
            range = endValue - iv.constantInit();
            absStride = iv.constantStride();
        } else {
            assert iv.direction() == Direction.Down;
            if (helper.compare(initValue, endValue) < 0) {
                return 0;
            }
            range = iv.constantInit() - endValue;
            absStride = -iv.constantStride();
        }
        if (oneOff) {
            range += 1;
        }
        long denominator = range + absStride - 1;
        return Long.divideUnsigned(denominator, absStride);
    }

    public IntegerHelper getCounterIntegerHelper() {
        IntegerStamp stamp = (IntegerStamp) iv.valueNode().stamp(NodeView.DEFAULT);
        return getCounterIntegerHelper(stamp.getBits());
    }

    public IntegerHelper getCounterIntegerHelper(int bits) {
        IntegerHelper helper;
        if (isUnsignedCheck()) {
            helper = new UnsignedIntegerHelper(bits);
        } else {
            helper = new SignedIntegerHelper(bits);
        }
        return helper;
    }

    public boolean isExactTripCount() {
        return loop.loop().getNaturalExits().size() == 1;
    }

    public ValueNode exactTripCountNode() {
        assert isExactTripCount();
        return maxTripCountNode();
    }

    public boolean isConstantExactTripCount() {
        assert isExactTripCount();
        return isConstantMaxTripCount();
    }

    public UnsignedLong constantExactTripCount() {
        assert isExactTripCount();
        return constantMaxTripCount();
    }

    @Override
    public String toString() {
        return "iv=" + iv + " until " + end + (oneOff ? iv.direction() == Direction.Up ? "+1" : "-1" : "");
    }

    public ValueNode getLimit() {
        return end;
    }

    public IfNode getLimitTest() {
        return ifNode;
    }

    public ValueNode getStart() {
        return iv.initNode();
    }

    public boolean isLimitIncluded() {
        return oneOff;
    }

    public AbstractBeginNode getBody() {
        return body;
    }

    public AbstractBeginNode getCountedExit() {
        if (getLimitTest().trueSuccessor() == getBody()) {
            return getLimitTest().falseSuccessor();
        } else {
            assert getLimitTest().falseSuccessor() == getBody();
            return getLimitTest().trueSuccessor();
        }
    }

    public Direction getDirection() {
        return iv.direction();
    }

    public InductionVariable getCounter() {
        return iv;
    }

    public GuardingNode getOverFlowGuard() {
        return loop.loopBegin().getOverflowGuard();
    }

    public boolean counterNeverOverflows() {
        if (loop.loopBegin().canNeverOverflow()) {
            return true;
        }
        if (iv.isConstantStride() && abs(iv.constantStride()) == 1) {
            return true;
        }
        // @formatter:off
        /*
         * Following comment reasons about the simplest possible loop form:
         *
         *              for(i = 0;i < end;i += stride)
         *
         * The problem is we want to create an overflow guard for the loop that can be hoisted
         * before the loop, i.e., the overflow guard must not have loop variant inputs else it must
         * be scheduled inside the loop. This means we cannot refer explicitly to the induction
         * variable's phi but must establish a relation between end, stride and max (max integer
         * range for a given loop) that is sufficient for most cases.
         *
         * We know that a head counted loop with a stride > 1 may overflow if the stride is big
         * enough that end + stride will be > MAX, i.e. it overflows into negative value range.
         *
         * It is important that "end" in this context is the checked value of the loop condition:
         * i.e., an arbitrary value. There is no relation between end and MAX established except
         * that based on the integer representation we know that end <= MAX.
         *
         * A loop can overflow if the last checked value of the iv allows an overflow in the next
         * iteration: the value range for which an overflow can happen is [MAX-(stride-1),MAX] e.g.
         *
         * MAX=10, stride = 3, overflow if number > 10
         *  end = MAX -> 10 -> 10 + 3 = 13 -> overflow
         *  end = MAX-1 -> 9 -> 9 + 3 = 12 -> overflow
         *  end = MAX-2 -> 8 -> 8 + 3 = 11 -> overflow
         *  end = MAX-3 -> 7 -> 7 + 3 = 10 -> No overflow at MAX - stride
         *
         * Note that this guard is pessimistic, i.e., it marks loops as potentially overflowing that
         * are actually not overflowing. Consider the following loop:
         *
         * <pre>
         *    for(i = MAX-56; i < MAX, i += 8)
         * </pre>
         *
         *  where i in last loop body visit = MAX - 8, i after = MAX, no overflow
         *
         * which is wrongly detected as overflowing since "end" is element of [MAX-(stride-1),MAX]
         * which is [MAX-7,MAX] and end is MAX. We handle such cases with a speculation and disable
         * counted loop detection on subsequent compilations. We can only avoid such false positive
         * detections by actually computing the number of iterations with a division, however we try
         * to avoid that since that may be part of the fast path.
         *
         * And additional backup strategy could be to actually emit the precise guard inside the
         * loop if the deopt already failed, but we refrain from this for now for simplicity
         * reasons.
         */
        // @formatter:on
        IntegerStamp endStamp = (IntegerStamp) end.stamp(NodeView.DEFAULT);
        ValueNode strideNode = iv.strideNode();
        IntegerStamp strideStamp = (IntegerStamp) strideNode.stamp(NodeView.DEFAULT);
        IntegerHelper integerHelper = getCounterIntegerHelper();
        if (getDirection() == Direction.Up) {
            long max = integerHelper.maxValue();
            return integerHelper.compare(endStamp.upperBound(), max - (strideStamp.upperBound() - 1) - (oneOff ? 1 : 0)) <= 0;
        } else if (getDirection() == Direction.Down) {
            long min = integerHelper.minValue();
            return integerHelper.compare(min + (1 - strideStamp.lowerBound()) + (oneOff ? 1 : 0), endStamp.lowerBound()) <= 0;
        }
        return false;
    }

    @SuppressWarnings("try")
    public GuardingNode createOverFlowGuard() {
        GuardingNode overflowGuard = getOverFlowGuard();
        if (overflowGuard != null || counterNeverOverflows()) {
            return overflowGuard;
        }
        try (DebugCloseable position = loop.loopBegin().withNodeSourcePosition()) {
            IntegerStamp stamp = (IntegerStamp) iv.valueNode().stamp(NodeView.DEFAULT);
            IntegerHelper integerHelper = getCounterIntegerHelper();
            StructuredGraph graph = iv.valueNode().graph();
            LogicNode cond; // we use a negated guard with a < condition to achieve a >=
            ConstantNode one = ConstantNode.forIntegerStamp(stamp, 1, graph);
            if (iv.direction() == Direction.Up) {
                ValueNode v1 = sub(ConstantNode.forIntegerStamp(stamp, integerHelper.maxValue()), sub(iv.strideNode(), one));
                if (oneOff) {
                    v1 = sub(v1, one);
                }
                cond = graph.addOrUniqueWithInputs(integerHelper.createCompareNode(v1, end, NodeView.DEFAULT));
            } else {
                assert iv.direction() == Direction.Down;
                ValueNode v1 = add(ConstantNode.forIntegerStamp(stamp, integerHelper.minValue()), sub(one, iv.strideNode()));
                if (oneOff) {
                    v1 = add(v1, one);
                }
                cond = graph.addOrUniqueWithInputs(integerHelper.createCompareNode(end, v1, NodeView.DEFAULT));
            }
            assert graph.getGuardsStage().allowsFloatingGuards();

            SpeculationLog speculationLog = graph.getSpeculationLog();
            SpeculationLog.Speculation speculation = SpeculationLog.NO_SPECULATION;
            if (speculationLog != null) {
                SpeculationLog.SpeculationReason speculationReason = LoopBeginNode.LOOP_OVERFLOW_DEOPT.createSpeculationReason(graph.method(), iv.loop.loopBegin().stateAfter().bci);
                if (speculationLog.maySpeculate(speculationReason)) {
                    speculation = speculationLog.speculate(speculationReason);
                    LoopBeginNode.overflowSpeculationTaken.increment(graph.getDebug());
                } else {
                    GraalError.shouldNotReachHere("Must not create overflow guard for loop " + loop.loopBegin() + " where the speculation guard already failed, this can create deopt loops");
                }
            }

            overflowGuard = graph.unique(new GuardNode(cond, AbstractBeginNode.prevBegin(loop.entryPoint()), DeoptimizationReason.LoopLimitCheck, DeoptimizationAction.InvalidateRecompile, true,
                            speculation, null));
            loop.loopBegin().setOverflowGuard(overflowGuard);
            return overflowGuard;
        }
    }

    public IntegerStamp getStamp() {
        return (IntegerStamp) iv.valueNode().stamp(NodeView.DEFAULT);
    }

    public boolean isInverted() {
        return false;
    }
}
