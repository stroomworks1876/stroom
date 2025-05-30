/*
 * Copyright 2017 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.query.common.v2.format;


import stroom.query.language.functions.Val;
import stroom.util.shared.NullSafe;

import java.util.Objects;

public class Unformatted implements Formatter {

    private static Unformatted INSTANCE = new Unformatted();

    private Unformatted() {
    }

    public static Unformatted create() {
        return INSTANCE;
    }

    @Override
    public String format(final Val value) {
        return NullSafe.get(value, Objects::toString);
    }
}
