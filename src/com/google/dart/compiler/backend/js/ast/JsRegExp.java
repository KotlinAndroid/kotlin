// Copyright (c) 2011, the Dart project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.google.dart.compiler.backend.js.ast;

/**
 * A JavaScript regular expression.
 */
public final class JsRegExp extends JsLiteral.JsValueLiteral {
    private String flags;
    private String pattern;

    public JsRegExp() {
    }

    public String getFlags() {
        return flags;
    }

    public String getPattern() {
        return pattern;
    }

    public void setFlags(String suffix) {
        flags = suffix;
    }

    public void setPattern(String re) {
        pattern = re;
    }

    @Override
    public void accept(JsVisitor v, JsContext context) {
        v.visitRegExp(this, context);
    }
}
