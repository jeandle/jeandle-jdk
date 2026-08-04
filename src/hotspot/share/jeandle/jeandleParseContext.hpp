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

#ifndef SHARE_JEANDLE_PARSE_CONTEXT_HPP
#define SHARE_JEANDLE_PARSE_CONTEXT_HPP

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethod.hpp"
#include "utilities/debug.hpp"

class JeandleParseContext {
 public:
  enum Role {
    RootMethod,
    InlineeMethod
  };

  JeandleParseContext() :
      _role(RootMethod),
      _method(nullptr) {}

  JeandleParseContext(Role role, ciMethod* method) :
      _role(role),
      _method(method) {
    assert(method != nullptr, "parse context must have a method");
  }

  static JeandleParseContext root(ciMethod* method) {
    return JeandleParseContext(RootMethod, method);
  }

  static JeandleParseContext inlinee(ciMethod* method) {
    return JeandleParseContext(InlineeMethod, method);
  }

  bool is_root() const { return _role == RootMethod; }
  bool is_inlinee() const { return _role == InlineeMethod; }

  ciMethod* method() const { return _method; }

 private:
  Role _role;
  ciMethod* _method;
};

#endif // SHARE_JEANDLE_PARSE_CONTEXT_HPP
