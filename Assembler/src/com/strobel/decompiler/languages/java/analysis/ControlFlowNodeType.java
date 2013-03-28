/*
 * ControlFlowNodeType.java
 *
 * Copyright (c) 2013 Mike Strobel
 *
 * This source code is subject to terms and conditions of the Apache License, Version 2.0.
 * A copy of the license can be found in the License.html file at the root of this distribution.
 * By using this source code in any fashion, you are agreeing to be bound by the terms of the
 * Apache License, Version 2.0.
 *
 * You must not remove this notice, or any other, from this software.
 */

package com.strobel.decompiler.languages.java.analysis;

public enum ControlFlowNodeType {
    /**
     * Unknown node type
     */
    None,

    /**
     * Node in front of a statement
     */
    StartNode,

    /**
     * Node between two statements
     */
    BetweenStatements,

    /**
     * Node at the end of a statement list
     */
    EndNode,

    /**
     * Node representing the position before evaluating the condition of a loop.
     */
    LoopCondition
}