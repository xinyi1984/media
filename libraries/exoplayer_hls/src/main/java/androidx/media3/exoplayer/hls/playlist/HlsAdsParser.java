/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.hls.playlist;

import android.text.TextUtils;
import androidx.media3.common.C;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HlsAdsParser {

  private static final String TAG = HlsAdsParser.class.getSimpleName();

  private static final String TAG_DURATION = "#EXTINF";
  private static final String TAG_ENDLIST = "#EXT-X-ENDLIST";
  private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";
  private static final String DEFAULT_GROUP_IDENTIFIER = "NO_PATH";

  private static final int REASONABLE_GROUP_LIMIT = 10;
  private static final int MIN_PREFIX_LENGTH_TO_TEST = 5;
  private static final int SEQUENCE_NUMBER_RESERVED_LENGTH = 4;

  private static final int AD_BREAK_THRESHOLD_SHORT = 3;
  private static final int AD_BREAK_THRESHOLD_MEDIUM = 4;
  private static final int AD_BREAK_THRESHOLD_LONG = 5;
  private static final int AD_BREAK_THRESHOLD_EXTRA = 6;

  private static final double MIN_MAJORITY_GROUP_RATIO = 0.85;
  private static final double AD_BLOCK_SIZE_RATIO = 0.75;
  private static final double DURATION_TIER_SHORT = 30.0;
  private static final double DURATION_TIER_MEDIUM = 60.0;
  private static final double DURATION_TIER_LONG = 90.0;

  public static String process(String m3u8) {
    if (TextUtils.isEmpty(m3u8) || !m3u8.contains(TAG_ENDLIST)) {
      return m3u8;
    }
    Log.d(TAG, "開始執行 HlsAdsParser。");
    String[] lines = Util.split(m3u8, "\\r?\\n");
    Set<Integer> adSegmentIndexes = findAds(lines);
    if (adSegmentIndexes.isEmpty()) {
      Log.d(TAG, "沒有偵測到廣告片段，保留原始播放清單。");
      return m3u8;
    }
    Log.d(TAG, "偵測到 " + adSegmentIndexes.size() + " 個廣告片段，開始重建播放清單。");
    return rebuildM3u8(lines, adSegmentIndexes, m3u8.length());
  }

  private static Set<Integer> findAds(String[] lines) {
    Log.d(TAG, "執行主要策略：檔名與路徑分析。");
    List<String> allSegments = getSegments(lines);
    Log.d(TAG, "播放清單共有 " + allSegments.size() + " 個媒體片段。");
    Set<Integer> adsFromFilename = findAdsByFilename(allSegments);
    if (!adsFromFilename.isEmpty()) {
      Log.d(TAG, "主要策略命中，使用檔名與路徑分析結果。");
      return adsFromFilename;
    }
    Log.d(TAG, "主要策略未找到廣告，改用不連續標籤分析。");
    return findAdsByDiscontinuity(lines);
  }

  private static List<String> getSegments(String[] lines) {
    List<String> segments = new ArrayList<>(lines.length);
    for (String line : lines) {
      String trimmedLine = line.trim();
      if (isSegmentLine(trimmedLine)) {
        segments.add(trimmedLine);
      }
    }
    return segments;
  }

  private static Set<Integer> findAdsByDiscontinuity(String[] lines) {
    List<List<Integer>> blocks = getDiscontinuityBlocks(lines);
    if (blocks.size() < 2) {
      Log.d(TAG, "不連續標籤分析只有 " + blocks.size() + " 個區塊，無法判斷。");
      return Collections.emptySet();
    }
    Log.d(TAG, "不連續標籤分析找到 " + blocks.size() + " 個區塊。");
    List<List<Integer>> analysisBlocks = blocks.subList(0, blocks.size() - 1);
    int modeSize = getModeSize(analysisBlocks);
    if (modeSize <= 0) {
      Log.d(TAG, "不連續標籤分析無法取得有效眾數區塊大小。");
      return Collections.emptySet();
    }
    int minorityBlockCount = 0;
    Set<Integer> adSegmentIndexes = new HashSet<>(analysisBlocks.size());
    double adSizeThreshold = modeSize * AD_BLOCK_SIZE_RATIO;
    int maxBlockSize = 0;
    for (List<Integer> block : analysisBlocks) {
      int size = block.size();
      maxBlockSize = Math.max(maxBlockSize, size);
      if (size < adSizeThreshold) {
        minorityBlockCount++;
        adSegmentIndexes.addAll(block);
      }
    }
    if (minorityBlockCount == 0 && modeSize * 2 < maxBlockSize) {
      Log.d(TAG, "不連續標籤分析：眾數區塊大小為 " + modeSize + "，明顯小於最大區塊 " + maxBlockSize + "，改將不大於眾數大小的區塊視為廣告。");
      for (List<Integer> block : analysisBlocks) {
        if (block.size() <= modeSize) {
          minorityBlockCount++;
          adSegmentIndexes.addAll(block);
        }
      }
    }
    double totalDurationMinutes = getTotalDurationInMinutes(lines);
    int minorityCountThreshold = getMinorityCountThreshold(totalDurationMinutes);
    Log.d(TAG, "播放清單總時長約 " + Util.formatInvariant("%.2f", totalDurationMinutes) + " 分鐘，廣告區塊上限為 " + minorityCountThreshold + "。");
    if (minorityBlockCount > 0 && minorityBlockCount <= minorityCountThreshold) {
      Log.d(TAG, "不連續標籤分析找到 " + minorityBlockCount + " 個廣告區塊，眾數區塊大小為 " + modeSize + "，已排除最後一個區塊。");
      return adSegmentIndexes;
    }
    Log.d(TAG, "不連續標籤分析找到 " + minorityBlockCount + " 個可疑廣告區塊，超過上限 " + minorityCountThreshold + " 或為 0，判定結果不明確，忽略。");
    return Collections.emptySet();
  }

  private static int getModeSize(List<List<Integer>> blocks) {
    Map<Integer, Integer> sizeFrequencies = new HashMap<>(blocks.size());
    for (List<Integer> block : blocks) {
      int size = block.size();
      Integer count = sizeFrequencies.get(size);
      sizeFrequencies.put(size, count == null ? 1 : count + 1);
    }
    int modeSize = C.INDEX_UNSET;
    int maxFrequency = C.INDEX_UNSET;
    for (Map.Entry<Integer, Integer> entry : sizeFrequencies.entrySet()) {
      int frequency = entry.getValue();
      int size = entry.getKey();
      if (frequency > maxFrequency || (frequency == maxFrequency && size > modeSize)) {
        maxFrequency = frequency;
        modeSize = size;
      }
    }
    return modeSize;
  }

  private static double getTotalDurationInMinutes(String[] lines) {
    double totalSeconds = 0.0;
    for (String line : lines) {
      if (line.startsWith(TAG_DURATION)) {
        try {
          totalSeconds += parseExtInfDurationSeconds(line);
        } catch (NumberFormatException ignored) {
          // Ignore malformed EXTINF durations. This value is only used for ad heuristics.
        }
      }
    }
    return totalSeconds / 60.0;
  }

  private static double parseExtInfDurationSeconds(String line) {
    int durationStartIndex = TAG_DURATION.length() + 1;
    if (line.length() <= durationStartIndex || line.charAt(TAG_DURATION.length()) != ':') {
      throw new NumberFormatException();
    }
    int durationEndIndex = line.indexOf(',', durationStartIndex);
    return Double.parseDouble(durationEndIndex == C.INDEX_UNSET ? line.substring(durationStartIndex) : line.substring(durationStartIndex, durationEndIndex));
  }

  private static int getMinorityCountThreshold(double totalMinutes) {
    if (totalMinutes <= DURATION_TIER_SHORT) {
      return AD_BREAK_THRESHOLD_SHORT;
    }
    if (totalMinutes <= DURATION_TIER_MEDIUM) {
      return AD_BREAK_THRESHOLD_MEDIUM;
    }
    if (totalMinutes <= DURATION_TIER_LONG) {
      return AD_BREAK_THRESHOLD_LONG;
    }
    return AD_BREAK_THRESHOLD_EXTRA;
  }

  private static List<List<Integer>> getDiscontinuityBlocks(String[] lines) {
    List<List<Integer>> blocks = new ArrayList<>();
    List<Integer> currentBlock = new ArrayList<>();
    int segmentIndex = 0;
    for (String line : lines) {
      String trimmedLine = line.trim();
      if (trimmedLine.equals(TAG_DISCONTINUITY)) {
        if (!currentBlock.isEmpty()) {
          blocks.add(currentBlock);
        }
        currentBlock = new ArrayList<>();
      } else if (isSegmentLine(trimmedLine)) {
        currentBlock.add(segmentIndex++);
      }
    }
    if (!currentBlock.isEmpty()) {
      blocks.add(currentBlock);
    }
    return blocks;
  }

  private static Set<Integer> findAdsByFilename(List<String> allSegments) {
    if (allSegments.size() < 2) {
      Log.d(TAG, "片段數少於 2，略過檔名與路徑分析。");
      return Collections.emptySet();
    }
    Map<String, List<Integer>> structuralGroups = groupIndexesBy(allSegments, HlsAdsParser::getStructuralIdentifier);
    Log.d(TAG, "路徑結構分成 " + structuralGroups.size() + " 組。");
    if (structuralGroups.size() > 1 && structuralGroups.size() <= REASONABLE_GROUP_LIMIT) {
      return findMinorityGroup(structuralGroups);
    }
    Log.d(TAG, "路徑結構分組不適合判斷，改用前綴分析。");
    return findAdsByPrefixAnalysis(allSegments);
  }

  private static String getStructuralIdentifier(String segmentUrl) {
    int schemeEnd = segmentUrl.indexOf("://");
    if (schemeEnd != C.INDEX_UNSET) {
      int hostEnd = segmentUrl.indexOf('/', schemeEnd + 3);
      return hostEnd != C.INDEX_UNSET ? segmentUrl.substring(0, hostEnd) : segmentUrl;
    }
    int lastSlashIndex = segmentUrl.lastIndexOf('/');
    return lastSlashIndex != C.INDEX_UNSET ? segmentUrl.substring(0, lastSlashIndex) : DEFAULT_GROUP_IDENTIFIER;
  }

  private static Set<Integer> findMinorityGroup(Map<String, List<Integer>> groups) {
    int totalSize = 0;
    for (List<Integer> segmentIndexes : groups.values()) {
      totalSize += segmentIndexes.size();
    }
    int maxSize = getMaxGroupSize(groups);
    if (maxSize * 2 <= totalSize) {
      Log.d(TAG, "少數群組分析沒有明確主群組（" + maxSize + "/" + totalSize + "），略過。");
      return Collections.emptySet();
    }
    Set<Integer> adSegmentIndexes = new HashSet<>(groups.size());
    for (List<Integer> segmentIndexes : groups.values()) {
      if (segmentIndexes.size() < maxSize) {
        adSegmentIndexes.addAll(segmentIndexes);
      }
    }
    return adSegmentIndexes;
  }

  private static Set<Integer> findAdsByPrefixAnalysis(List<String> segments) {
    int optimalPrefixLength = findOptimalPrefixLength(segments);
    if (optimalPrefixLength == C.INDEX_UNSET) {
      Log.d(TAG, "前綴分析找不到有效前綴長度。");
      return Collections.emptySet();
    }
    Log.d(TAG, "前綴分析選用長度 " + optimalPrefixLength + "。");
    Map<String, List<Integer>> groups = groupIndexesBy(segments, segment -> getPrefix(segment, optimalPrefixLength));
    if (groups.size() <= 1 || groups.size() > REASONABLE_GROUP_LIMIT) {
      Log.d(TAG, "前綴分析分成 " + groups.size() + " 組，分組數不適合判斷。");
      return Collections.emptySet();
    }
    return findMinorityGroup(groups);
  }

  private static int findOptimalPrefixLength(List<String> segments) {
    if (segments.size() < 2) {
      return C.INDEX_UNSET;
    }
    int shortestSegmentLength = Integer.MAX_VALUE;
    for (String segment : segments) {
      shortestSegmentLength = Math.min(shortestSegmentLength, segment.length());
    }
    int bestLength = C.INDEX_UNSET;
    double highestScore = 0.0;
    int maxLength = shortestSegmentLength - SEQUENCE_NUMBER_RESERVED_LENGTH;
    for (int length = MIN_PREFIX_LENGTH_TO_TEST; length < maxLength; length++) {
      final int prefixLength = length;
      Map<String, List<Integer>> groups = groupIndexesBy(segments, segment -> getPrefix(segment, prefixLength));
      int groupCount = groups.size();
      if (groupCount <= 1 || groupCount > REASONABLE_GROUP_LIMIT) {
        continue;
      }
      int maxGroupSize = getMaxGroupSize(groups);
      double score = (double) maxGroupSize / segments.size();
      if (score >= MIN_MAJORITY_GROUP_RATIO && score > highestScore) {
        highestScore = score;
        bestLength = length;
      }
    }
    return bestLength;
  }

  private static String getPrefix(String segment, int prefixLength) {
    return segment.length() > prefixLength ? segment.substring(0, prefixLength) : segment;
  }

  private static Map<String, List<Integer>> groupIndexesBy(List<String> segments, Classifier keyFn) {
    Map<String, List<Integer>> groups = new HashMap<>(segments.size());
    for (int i = 0; i < segments.size(); i++) {
      String key = keyFn.classify(segments.get(i));
      List<Integer> group = groups.get(key);
      if (group == null) {
        group = new ArrayList<>();
        groups.put(key, group);
      }
      group.add(i);
    }
    return groups;
  }

  private static int getMaxGroupSize(Map<String, List<Integer>> groups) {
    int maxSize = 0;
    for (List<Integer> group : groups.values()) {
      maxSize = Math.max(maxSize, group.size());
    }
    return maxSize;
  }

  private static boolean isSegmentLine(String trimmedLine) {
    return !trimmedLine.isEmpty() && !trimmedLine.startsWith("#");
  }

  private static String rebuildM3u8(String[] lines, Set<Integer> adSegmentIndexes, int originalLength) {
    List<String> stripped = removeAdSegments(lines, adSegmentIndexes);
    List<String> cleaned = removeOrphanedDiscontinuityTags(stripped);
    Log.d(TAG, "播放清單重建完成，行數由 " + lines.length + " 減少為 " + cleaned.size() + "。");
    StringBuilder builder = new StringBuilder(originalLength);
    for (String line : cleaned) {
      builder.append(line).append("\n");
    }
    return builder.toString();
  }

  private static List<String> removeAdSegments(String[] lines, Set<Integer> adSegmentIndexes) {
    List<String> result = new ArrayList<>(lines.length);
    boolean skippingAdSegment = false;
    int segmentIndex = 0;
    for (String sourceLine : lines) {
      String line = sourceLine.trim();
      if (line.isEmpty()) {
        continue;
      }
      if (line.startsWith(TAG_DURATION)) {
        if (adSegmentIndexes.contains(segmentIndex)) {
          skippingAdSegment = true;
          continue;
        }
      }
      if (isSegmentLine(line)) {
        segmentIndex++;
        if (skippingAdSegment || adSegmentIndexes.contains(segmentIndex - 1)) {
          skippingAdSegment = false;
          continue;
        }
      } else if (skippingAdSegment) {
        continue;
      }
      result.add(line);
    }
    return result;
  }

  private static List<String> removeOrphanedDiscontinuityTags(List<String> lines) {
    List<String> result = new ArrayList<>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.equals(TAG_DISCONTINUITY)) {
        boolean prevIsDiscontinuityOrBoundary = i == 0 || lines.get(i - 1).equals(TAG_DISCONTINUITY);
        String nextLine = i + 1 < lines.size() ? lines.get(i + 1) : null;
        boolean nextIsDiscontinuityOrBoundary = nextLine == null || nextLine.equals(TAG_DISCONTINUITY) || nextLine.equals(TAG_ENDLIST);
        if (prevIsDiscontinuityOrBoundary || nextIsDiscontinuityOrBoundary) {
          continue;
        }
      }
      result.add(line);
    }
    return result;
  }

  private interface Classifier {

    String classify(String segment);
  }
}
