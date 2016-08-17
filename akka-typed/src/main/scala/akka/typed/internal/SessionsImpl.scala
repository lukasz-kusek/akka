/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

/**
 * Implementation notes:
 *
 * Interpreter:
 *
 *  - a process is a tree of AST nodes, where each leaf is a producer of a process
 *  - interpreter has a list of currently existing processes
 *  - processes may be active (i.e. waiting for external input) or passive (i.e.
 *    waiting for internal progress)
 *  - external messages and internal completions are events that are enqueued
 *  - event processing runs in FIFO order, which ensures some fairness between
 *    concurrent processes
 *  - what is stored is actually a Traversal of a tree which keeps the back-links
 *    pointing towards the root; this is cheaper than rewriting the trees
 *  - the maximum event queue size is bounded by #channels + #processes (multiple
 *    events from the same channel can be coalesced inside that channel by a counter)
 *  - this way even complex process trees can be executed with minimal allocations
 *    (fixed-size preallocated arrays for event queue and back-links, preallocated
 *    processes can even be reentrant due to separate unique Traversals)
 */
private[typed] trait ProcessImpl[+T] extends Sessions.Process[T]
