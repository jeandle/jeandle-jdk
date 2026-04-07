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

#include "jeandle/jeandleInlinePolicy.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethod.hpp"
#include "jeandle/jeandle_globals.hpp"
#include "logging/log.hpp"

JeandleInlinePolicy::JeandleInlinePolicy(ciMethod* caller) :
    _caller(caller),
    _inline_depth(0),
    _max_inline_depth(JeandleMaxInlineDepth) {}

JeandleInlinePolicy::JeandleInlinePolicy(ciMethod* caller, int inline_depth, int max_inline_depth) :
    _caller(caller),
    _inline_depth(inline_depth),
    _max_inline_depth(max_inline_depth) {}

bool JeandleInlinePolicy::should_inline(ciMethod* callee, int bci) const {
  // Global inlining switch.
  if (!JeandleInline) {
    return false;
  }

  // Check inline depth limit.
  if (_inline_depth >= _max_inline_depth) {
    if (JeandleTraceInlining) {
      log_info(jeandle)("Not inlining: max inline depth %d reached at bci %d", _max_inline_depth, bci);
    }
    return false;
  }

  // Basic eligibility checks.
  if (!pass_initial_checks(callee)) {
    return false;
  }

  // Check bytecode size limit.
  if (callee->code_size() > JeandleMaxInlineSize) {
    if (JeandleTraceInlining) {
      ResourceMark rm;
      stringStream ss;
      callee->print_name(&ss);
      log_info(jeandle)("Not inlining `%s`: bytecode size %d exceeds limit %d",
                        ss.as_string(), callee->code_size(), (int)JeandleMaxInlineSize);
    }
    return false;
  }

  // Don't inline recursive calls (caller == callee).
  // TODO(inline): This only checks direct recursion (A→A). Indirect
  // recursion (A→B→A) across inline depths is not detected — need to walk the
  // caller chain to catch cycles.
  if (_caller == callee) {
    if (JeandleTraceInlining) {
      ResourceMark rm;
      stringStream ss;
      callee->print_name(&ss);
      log_info(jeandle)("Not inlining `%s`: recursive call", ss.as_string());
    }
    return false;
  }

  if (JeandleTraceInlining) {
    ResourceMark rm;
    stringStream ss;
    callee->print_name(&ss);
    log_info(jeandle)("Inlining `%s` at bci %d (depth %d)", ss.as_string(), bci, _inline_depth);
  }

  return true;
}

JeandleInlinePolicy JeandleInlinePolicy::child_policy(ciMethod* callee) const {
  return JeandleInlinePolicy(callee, _inline_depth + 1, _max_inline_depth);
}

bool JeandleInlinePolicy::pass_initial_checks(ciMethod* callee) const {
  // Callee must be loaded.
  if (!callee->is_loaded()) {
    if (JeandleTraceInlining) {
      log_info(jeandle)("Not inlining: callee not loaded");
    }
    return false;
  }

  // Cannot inline native methods.
  if (callee->is_native()) {
    if (JeandleTraceInlining) {
      ResourceMark rm;
      stringStream ss;
      callee->print_name(&ss);
      log_info(jeandle)("Not inlining `%s`: native method", ss.as_string());
    }
    return false;
  }

  // Cannot inline abstract methods.
  if (callee->is_abstract()) {
    if (JeandleTraceInlining) {
      ResourceMark rm;
      stringStream ss;
      callee->print_name(&ss);
      log_info(jeandle)("Not inlining `%s`: abstract method", ss.as_string());
    }
    return false;
  }

  // Callee must have balanced monitors.
  if (!callee->has_balanced_monitors()) {
    if (JeandleTraceInlining) {
      ResourceMark rm;
      stringStream ss;
      callee->print_name(&ss);
      log_info(jeandle)("Not inlining `%s`: unbalanced monitors", ss.as_string());
    }
    return false;
  }

  // Callee must be parseable.
  if (!callee->can_be_parsed()) {
    if (JeandleTraceInlining) {
      ResourceMark rm;
      stringStream ss;
      callee->print_name(&ss);
      log_info(jeandle)("Not inlining `%s`: cannot be parsed", ss.as_string());
    }
    return false;
  }

  // Callee's holder must be initialized (or being initialized).
  ciInstanceKlass* holder = callee->holder();
  if (!holder->is_being_initialized() &&
      !holder->is_initialized() &&
      !holder->is_interface()) {
    if (JeandleTraceInlining) {
      ResourceMark rm;
      stringStream ss;
      callee->print_name(&ss);
      log_info(jeandle)("Not inlining `%s`: holder not initialized", ss.as_string());
    }
    return false;
  }

  // TODO(inline): Synchronized method inlining requires inserting monitor_enter at
  // callee entry and monitor_exit at every return/exception path. Must ensure enter/exit
  // pairing is correct even with multiple return paths, exception handlers, and deoptimization.
  if (callee->is_synchronized()) {
    if (JeandleTraceInlining) {
      ResourceMark rm;
      stringStream ss;
      callee->print_name(&ss);
      log_info(jeandle)("Not inlining `%s`: synchronized method (not yet supported)", ss.as_string());
    }
    return false;
  }

  return true;
}
