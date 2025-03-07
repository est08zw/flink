/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.parse;

import org.apache.flink.table.api.TableException;
import org.apache.flink.table.operations.Operation;
import org.apache.flink.table.operations.command.SetOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strategy to parse statement to {@link SetOperation}. */
public class SetOperationParseStrategy extends AbstractRegexParseStrategy {

    static final SetOperationParseStrategy INSTANCE = new SetOperationParseStrategy();

    protected SetOperationParseStrategy() {
        super(Pattern.compile("SET(\\s+(\\S+)\\s*=(.+))?", DEFAULT_PATTERN_FLAGS));
    }

    @Override
    public Operation convert(String statement) {
        Matcher matcher = pattern.matcher(statement.trim());
        final List<String> operands = new ArrayList<>();
        if (matcher.find()) {
            for (int i = 0; i < matcher.groupCount(); i++) {
                if (matcher.group(i + 1) != null) {
                    operands.add(matcher.group(i + 1));
                }
            }
        }

        // only capture SET
        if (operands.isEmpty()) {
            return new SetOperation(new String[0]);
        } else if (operands.size() == 3) {
            return new SetOperation(
                    operands.subList(1, 3).stream().map(String::trim).toArray(String[]::new));
        } else {
            // impossible
            throw new TableException(
                    String.format(
                            "Failed to convert the statement to SET operation: %s.", statement));
        }
    }

    @Override
    public String[] getHints() {
        return new String[] {"SET"};
    }
}
