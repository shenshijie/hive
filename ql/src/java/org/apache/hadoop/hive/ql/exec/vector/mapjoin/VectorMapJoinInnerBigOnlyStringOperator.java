/**
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

package org.apache.hadoop.hive.ql.exec.vector.mapjoin;

import java.io.IOException;
import java.util.Arrays;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.ql.exec.JoinUtil;
import org.apache.hadoop.hive.ql.exec.vector.VectorizationContext;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.exec.vector.expressions.VectorExpression;
import org.apache.hadoop.hive.ql.exec.vector.mapjoin.hashtable.VectorMapJoinHashTableResult;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;

// Single-Column String hash table import.
import org.apache.hadoop.hive.ql.exec.vector.mapjoin.hashtable.VectorMapJoinBytesHashMultiSet;

// Single-Column String specific imports.
import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.expressions.StringExpr;

/*
 * Specialized class for doing a vectorized map join that is an inner join on a Single-Column String
 * and only big table columns appear in the join result so a hash multi-set is used.
 */
public class VectorMapJoinInnerBigOnlyStringOperator extends VectorMapJoinInnerBigOnlyGenerateResultOperator {

  private static final long serialVersionUID = 1L;
  private static final Log LOG = LogFactory.getLog(VectorMapJoinInnerBigOnlyStringOperator.class.getName());
  private static final String CLASS_NAME = VectorMapJoinInnerBigOnlyStringOperator.class.getName();

  // (none)

  // The above members are initialized by the constructor and must not be
  // transient.
  //---------------------------------------------------------------------------

  // The hash map for this specialized class.
  private transient VectorMapJoinBytesHashMultiSet hashMultiSet;

  //---------------------------------------------------------------------------
  // Single-Column String specific members.
  //

  // The column number for this one column join specialization.
  private transient int singleJoinColumn;

  //---------------------------------------------------------------------------
  // Pass-thru constructors.
  //

  public VectorMapJoinInnerBigOnlyStringOperator() {
    super();
  }

  public VectorMapJoinInnerBigOnlyStringOperator(VectorizationContext vContext, OperatorDesc conf) throws HiveException {
    super(vContext, conf);
  }

  //---------------------------------------------------------------------------
  // Process Single-Column String Inner Big-Only Join on a vectorized row batch.
  //

  @Override
  public void process(Object row, int tag) throws HiveException {

    try {
      VectorizedRowBatch batch = (VectorizedRowBatch) row;

      alias = (byte) tag;

      if (needCommonSetup) {
        // Our one time process method initialization.
        commonSetup(batch);

        /*
         * Initialize Single-Column String members for this specialized class.
         */

        singleJoinColumn = bigTableKeyColumnMap[0];

        needCommonSetup = false;
      }

      if (needHashTableSetup) {
        // Setup our hash table specialization.  It will be the first time the process
        // method is called, or after a Hybrid Grace reload.

        /*
         * Get our Single-Column String hash multi-set information for this specialized class.
         */

        hashMultiSet = (VectorMapJoinBytesHashMultiSet) vectorMapJoinHashTable;

        needHashTableSetup = false;
      }

      batchCounter++;

      // Do the per-batch setup for an inner big-only join.

      // (Currently none)
      // innerBigOnlyPerBatchSetup(batch);

      // For inner joins, we may apply the filter(s) now.
      for(VectorExpression ve : bigTableFilterExpressions) {
        ve.evaluate(batch);
      }

      final int inputLogicalSize = batch.size;

      if (inputLogicalSize == 0) {
        if (LOG.isDebugEnabled()) {
          LOG.debug(CLASS_NAME + " batch #" + batchCounter + " empty");
        }
        return;
      }

      // Perform any key expressions.  Results will go into scratch columns.
      if (bigTableKeyExpressions != null) {
        for (VectorExpression ve : bigTableKeyExpressions) {
          ve.evaluate(batch);
        }
      }

      // We rebuild in-place the selected array with rows destine to be forwarded.
      int numSel = 0;

      /*
       * Single-Column String specific declarations.
       */

      // The one join column for this specialized class.
      BytesColumnVector joinColVector = (BytesColumnVector) batch.cols[singleJoinColumn];
      byte[][] vector = joinColVector.vector;
      int[] start = joinColVector.start;
      int[] length = joinColVector.length;

      /*
       * Single-Column String check for repeating.
       */

      // Check single column for repeating.
      boolean allKeyInputColumnsRepeating = joinColVector.isRepeating;

      if (allKeyInputColumnsRepeating) {

        /*
         * Repeating.
         */

        // All key input columns are repeating.  Generate key once.  Lookup once.
        // Since the key is repeated, we must use entry 0 regardless of selectedInUse.

        /*
         * Single-Column String specific repeated lookup.
         */

        byte[] keyBytes = vector[0];
        int keyStart = start[0];
        int keyLength = length[0];
        JoinUtil.JoinResult joinResult = hashMultiSet.contains(keyBytes, keyStart, keyLength, hashMultiSetResults[0]);

        /*
         * Common repeated join result processing.
         */

        if (LOG.isDebugEnabled()) {
          LOG.debug(CLASS_NAME + " batch #" + batchCounter + " repeated joinResult " + joinResult.name());
        }
        numSel = finishInnerBigOnlyRepeated(batch, joinResult, hashMultiSetResults[0]);
      } else {

        /*
         * NOT Repeating.
         */

        if (LOG.isDebugEnabled()) {
          LOG.debug(CLASS_NAME + " batch #" + batchCounter + " non-repeated");
        }

        // We remember any matching rows in matchs / matchSize.  At the end of the loop,
        // selected / batch.size will represent both matching and non-matching rows for outer join.
        // Only deferred rows will have been removed from selected.
        int selected[] = batch.selected;
        boolean selectedInUse = batch.selectedInUse;

        int hashMultiSetResultCount = 0;
        int allMatchCount = 0;
        int equalKeySeriesCount = 0;
        int spillCount = 0;

        /*
         * Single-Column String specific variables.
         */

        int saveKeyBatchIndex = -1;

        // We optimize performance by only looking up the first key in a series of equal keys.
        boolean haveSaveKey = false;
        JoinUtil.JoinResult saveJoinResult = JoinUtil.JoinResult.NOMATCH;

        // Logical loop over the rows in the batch since the batch may have selected in use.
        for (int logical = 0; logical < inputLogicalSize; logical++) {
          int batchIndex = (selectedInUse ? selected[logical] : logical);

          /*
           * Single-Column String get key.
           */

          // Implicit -- use batchIndex.

          /*
           * Equal key series checking.
           */

          if (!haveSaveKey ||
              StringExpr.compare(vector[saveKeyBatchIndex], start[saveKeyBatchIndex], length[saveKeyBatchIndex],
                                 vector[batchIndex], start[batchIndex], length[batchIndex]) != 0) {

            // New key.

            if (haveSaveKey) {
              // Move on with our counts.
              switch (saveJoinResult) {
              case MATCH:
                // We have extracted the count from the hash multi-set result, so we don't keep it.
                equalKeySeriesCount++;
                break;
              case SPILL:
                // We keep the hash multi-set result for its spill information.
                hashMultiSetResultCount++;
                break;
              case NOMATCH:
                break;
              }
            }

            // Regardless of our matching result, we keep that information to make multiple use
            // of it for a possible series of equal keys.
            haveSaveKey = true;

            /*
             * Single-Column String specific save key.
             */

            saveKeyBatchIndex = batchIndex;

            /*
             * Single-Column String specific lookup key.
             */

            byte[] keyBytes = vector[batchIndex];
            int keyStart = start[batchIndex];
            int keyLength = length[batchIndex];
            saveJoinResult = hashMultiSet.contains(keyBytes, keyStart, keyLength, hashMultiSetResults[hashMultiSetResultCount]);

            /*
             * Common inner big-only join result processing.
             */

            switch (saveJoinResult) {
            case MATCH:
              equalKeySeriesValueCounts[equalKeySeriesCount] = hashMultiSetResults[hashMultiSetResultCount].count();
              equalKeySeriesAllMatchIndices[equalKeySeriesCount] = allMatchCount;
              equalKeySeriesDuplicateCounts[equalKeySeriesCount] = 1;
              allMatchs[allMatchCount++] = batchIndex;
              // VectorizedBatchUtil.debugDisplayOneRow(batch, batchIndex, CLASS_NAME + " MATCH isSingleValue " + equalKeySeriesIsSingleValue[equalKeySeriesCount] + " currentKey " + currentKey);
              break;

            case SPILL:
              spills[spillCount] = batchIndex;
              spillHashMapResultIndices[spillCount] = hashMultiSetResultCount;
              spillCount++;
              break;

            case NOMATCH:
              // VectorizedBatchUtil.debugDisplayOneRow(batch, batchIndex, CLASS_NAME + " NOMATCH" + " currentKey " + currentKey);
              break;
            }
          } else {
            // Series of equal keys.

            switch (saveJoinResult) {
            case MATCH:
              equalKeySeriesDuplicateCounts[equalKeySeriesCount]++;
              allMatchs[allMatchCount++] = batchIndex;
              // VectorizedBatchUtil.debugDisplayOneRow(batch, batchIndex, CLASS_NAME + " MATCH duplicate");
              break;

            case SPILL:
              spills[spillCount] = batchIndex;
              spillHashMapResultIndices[spillCount] = hashMultiSetResultCount;
              spillCount++;
              break;

            case NOMATCH:
              // VectorizedBatchUtil.debugDisplayOneRow(batch, batchIndex, CLASS_NAME + " NOMATCH duplicate");
              break;
            }
          }
        }

        if (haveSaveKey) {
          // Update our counts for the last key.
          switch (saveJoinResult) {
          case MATCH:
            // We have extracted the count from the hash multi-set result, so we don't keep it.
            equalKeySeriesCount++;
            break;
          case SPILL:
            // We keep the hash multi-set result for its spill information.
            hashMultiSetResultCount++;
            break;
          case NOMATCH:
            break;
          }
        }

        if (LOG.isDebugEnabled()) {
          LOG.debug(CLASS_NAME +
              " allMatchs " + intArrayToRangesString(allMatchs, allMatchCount) +
              " equalKeySeriesValueCounts " + longArrayToRangesString(equalKeySeriesValueCounts, equalKeySeriesCount) +
              " equalKeySeriesAllMatchIndices " + intArrayToRangesString(equalKeySeriesAllMatchIndices, equalKeySeriesCount) +
              " equalKeySeriesDuplicateCounts " + intArrayToRangesString(equalKeySeriesDuplicateCounts, equalKeySeriesCount) +
              " spills " + intArrayToRangesString(spills, spillCount) +
              " spillHashMapResultIndices " + intArrayToRangesString(spillHashMapResultIndices, spillCount) +
              " hashMapResults " + Arrays.toString(Arrays.copyOfRange(hashMultiSetResults, 0, hashMultiSetResultCount)));
        }

        numSel = finishInnerBigOnly(batch,
            allMatchs, allMatchCount,
            equalKeySeriesValueCounts, equalKeySeriesAllMatchIndices,
            equalKeySeriesDuplicateCounts, equalKeySeriesCount,
            spills, spillHashMapResultIndices, spillCount,
            (VectorMapJoinHashTableResult[]) hashMultiSetResults, hashMultiSetResultCount);
      }

      batch.selectedInUse = true;
      batch.size =  numSel;

      if (batch.size > 0) {
        // Forward any remaining selected rows.
        forwardBigTableBatch(batch);
      }

    } catch (IOException e) {
      throw new HiveException(e);
    } catch (Exception e) {
      throw new HiveException(e);
    }
  }
}
