/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
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
 */

#ifndef SHARE_JEANDLE_INLINE_POLICY_HPP
#define SHARE_JEANDLE_INLINE_POLICY_HPP

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethod.hpp"
#include "memory/allocation.hpp"

// JeandleInlinePolicy: Decides whether a callee method should be inlined.
//
// This class encapsulates all inlining decisions, separating the inlining
// policy (should we inline?) from the inlining mechanism (how to inline?).
//
// Design notes:
// - Analogous to C2's InlineTree::ok_to_inline(), but simpler and focused
//   on jeandle's needs.
// - The policy is passed down through recursive inlining via child_policy().
// - Currently uses simple heuristics (depth, size, basic checks). Future
//   versions will incorporate profile data, call frequency, etc.
//
class JeandleInlinePolicy : public StackObj {
 public:
  // Construct a top-level policy for the root compilation method.
  JeandleInlinePolicy(ciMethod* caller);

  // Construct a child policy for recursive inlining.
  JeandleInlinePolicy(ciMethod* caller, int inline_depth, int max_inline_depth);

  // Core decision method: should the callee be inlined at the given bci?
  bool should_inline(ciMethod* callee, int bci) const;

  // Create a child policy for the next level of inlining.
  JeandleInlinePolicy child_policy(ciMethod* callee) const;

  // Accessors
  int inline_depth() const       { return _inline_depth; }
  int max_inline_depth() const   { return _max_inline_depth; }
  ciMethod* caller() const       { return _caller; }

 private:
  ciMethod* _caller;
  int _inline_depth;
  int _max_inline_depth;

  // Basic eligibility checks (method is loaded, not native, etc.)
  bool pass_initial_checks(ciMethod* callee) const;
};

#endif // SHARE_JEANDLE_INLINE_POLICY_HPP
