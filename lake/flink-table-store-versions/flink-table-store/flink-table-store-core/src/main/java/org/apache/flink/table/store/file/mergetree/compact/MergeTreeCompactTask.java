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

package org.apache.flink.table.store.file.mergetree.compact;

import org.apache.flink.table.data.RowData;
import org.apache.flink.table.store.file.compact.CompactResult;
import org.apache.flink.table.store.file.compact.CompactTask;
import org.apache.flink.table.store.file.compact.CompactUnit;
import org.apache.flink.table.store.file.data.DataFileMeta;
import org.apache.flink.table.store.file.mergetree.SortedRun;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static java.util.Collections.singletonList;

/** Compact task for merge tree compaction. */
public class MergeTreeCompactTask extends CompactTask {

    private final long minFileSize;
    private final CompactRewriter rewriter;
    private final int outputLevel;

    private final List<List<SortedRun>> partitioned;

    private final boolean dropDelete;

    // metric
    private int upgradeFilesNum;

    public MergeTreeCompactTask(
            Comparator<RowData> keyComparator,
            long minFileSize,
            CompactRewriter rewriter,
            CompactUnit unit,
            boolean dropDelete) {
        super(unit.files());
        this.minFileSize = minFileSize;
        this.rewriter = rewriter;
        this.outputLevel = unit.outputLevel();
        this.partitioned = new IntervalPartition(unit.files(), keyComparator).partition();
        this.dropDelete = dropDelete;

        this.upgradeFilesNum = 0;
    }

    @Override
    protected CompactResult doCompact(List<DataFileMeta> inputs) throws Exception {
        List<List<SortedRun>> candidate = new ArrayList<>();
        List<DataFileMeta> compactBefore = new ArrayList<>();
        List<DataFileMeta> compactAfter = new ArrayList<>();

        // Checking the order and compacting adjacent and contiguous files
        // Note: can't skip an intermediate file to compact, this will destroy the overall
        // orderliness
        for (List<SortedRun> section : partitioned) {
            if (section.size() > 1) {
                candidate.add(section);
            } else {
                SortedRun run = section.get(0);
                // No overlapping:
                // We can just upgrade the large file and just change the level instead of
                // rewriting it
                // But for small files, we will try to compact it
                for (DataFileMeta file : run.files()) {
                    if (file.fileSize() < minFileSize) {
                        // Smaller files are rewritten along with the previous files
                        candidate.add(singletonList(SortedRun.fromSingle(file)));
                    } else {
                        // Large file appear, rewrite previous and upgrade it
                        rewrite(candidate, compactBefore, compactAfter);
                        upgrade(file, compactBefore, compactAfter);
                    }
                }
            }
        }
        rewrite(candidate, compactBefore, compactAfter);
        return new CompactResult() {
            @Override
            public List<DataFileMeta> before() {
                return compactBefore;
            }

            @Override
            public List<DataFileMeta> after() {
                return compactAfter;
            }
        };
    }

    @Override
    protected String logMetric(
            long startMillis, List<DataFileMeta> compactBefore, List<DataFileMeta> compactAfter) {
        return String.format(
                "%s, upgrade file num = %d",
                super.logMetric(startMillis, compactBefore, compactAfter), upgradeFilesNum);
    }

    private void upgrade(
            DataFileMeta file, List<DataFileMeta> compactBefore, List<DataFileMeta> compactAfter) {
        if (file.level() != outputLevel) {
            compactBefore.add(file);
            compactAfter.add(file.upgrade(outputLevel));
            upgradeFilesNum++;
        }
    }

    private void rewrite(
            List<List<SortedRun>> candidate,
            List<DataFileMeta> compactBefore,
            List<DataFileMeta> compactAfter)
            throws Exception {
        if (candidate.isEmpty()) {
            return;
        }
        if (candidate.size() == 1) {
            List<SortedRun> section = candidate.get(0);
            if (section.size() == 0) {
                return;
            } else if (section.size() == 1) {
                for (DataFileMeta file : section.get(0).files()) {
                    upgrade(file, compactBefore, compactAfter);
                }
                candidate.clear();
                return;
            }
        }
        candidate.forEach(runs -> runs.forEach(run -> compactBefore.addAll(run.files())));
        List<DataFileMeta> result = rewriter.rewrite(outputLevel, dropDelete, candidate);
        compactAfter.addAll(result);
        candidate.clear();
    }
}
