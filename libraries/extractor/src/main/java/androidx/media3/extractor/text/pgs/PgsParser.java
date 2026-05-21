/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.extractor.text.pgs;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Format.CueReplacementBehavior;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.zip.Inflater;

/** A {@link SubtitleParser} for PGS subtitles. */
@UnstableApi
public final class PgsParser implements SubtitleParser {

  /**
   * The {@link CueReplacementBehavior} for consecutive {@link CuesWithTiming} emitted by this
   * implementation.
   */
  public static final @CueReplacementBehavior int CUE_REPLACEMENT_BEHAVIOR =
      Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE;

  private static final int SECTION_TYPE_PALETTE = 0x14;
  private static final int SECTION_TYPE_BITMAP_PICTURE = 0x15;
  private static final int SECTION_TYPE_IDENTIFIER = 0x16;
  private static final int SECTION_TYPE_WINDOW_DEFINITION = 0x17;
  private static final int SECTION_TYPE_END = 0x80;
  private static final int SUP_SEGMENT_MAGIC = 0x5047;
  private static final int SUP_SEGMENT_HEADER_SIZE = 13;
  private final ParsableByteArray buffer;
  private final ParsableByteArray inflatedBuffer;
  private final PgsCueBuilder cueBuilder;
  private final PgsSupSegment supSegment;
  @Nullable
  private Inflater inflater;

  public PgsParser() {
    buffer = new ParsableByteArray();
    inflatedBuffer = new ParsableByteArray();
    cueBuilder = new PgsCueBuilder();
    supSegment = new PgsSupSegment();
  }

  private static boolean isSupFileData(byte[] data, int offset, int length) {
    return length >= SUP_SEGMENT_HEADER_SIZE && (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF)) == SUP_SEGMENT_MAGIC;
  }

  private static long getDisplaySetStartTimeUs(long currentStartTimeUs, PgsSupSegment segment) {
    if (currentStartTimeUs == C.TIME_UNSET || segment.sectionType == SECTION_TYPE_IDENTIFIER) {
      return segment.ptsUs;
    }
    return currentStartTimeUs;
  }

  @Override
  public @CueReplacementBehavior int getCueReplacementBehavior() {
    return CUE_REPLACEMENT_BEHAVIOR;
  }

  @Override
  public void reset() {
    cueBuilder.reset();
  }

  @Override
  public void parse(
      byte[] data,
      int offset,
      int length,
      OutputOptions outputOptions,
      Consumer<CuesWithTiming> output) {
    if (isSupFileData(data, offset, length)) {
      parseSupFile(data, offset, length, outputOptions, output);
      return;
    }
    buffer.reset(data, /* limit= */ offset + length);
    buffer.setPosition(offset);
    if (inflater == null) {
      inflater = new Inflater();
    }
    if (Util.maybeInflate(buffer, inflatedBuffer, inflater)) {
      buffer.reset(inflatedBuffer.getData(), inflatedBuffer.limit());
    }
    ArrayList<Cue> cues = new ArrayList<>();
    boolean sawClearDisplaySet = false;
    while (buffer.bytesLeft() >= 3) {
      int limit = buffer.limit();
      int sectionType = buffer.readUnsignedByte();
      int sectionLength = buffer.readUnsignedShort();
      int nextSectionPosition = buffer.getPosition() + sectionLength;
      if (nextSectionPosition > limit) {
        buffer.setPosition(limit);
        break;
      }
      if (sectionType == SECTION_TYPE_END) {
        cueBuilder.build(cues);
        sawClearDisplaySet |= cueBuilder.isClearDisplaySet();
        cueBuilder.clearPresentation();
      } else {
        parsePresentationSection(sectionType, sectionLength);
      }
      buffer.setPosition(nextSectionPosition);
    }
    if (!cues.isEmpty()) {
      output.accept(
          new CuesWithTiming(cues, /* startTimeUs= */ C.TIME_UNSET, /* durationUs= */ C.TIME_UNSET));
    } else if (sawClearDisplaySet) {
      output.accept(
          new CuesWithTiming(
              ImmutableList.of(), /* startTimeUs= */ C.TIME_UNSET, /* durationUs= */ C.TIME_UNSET));
    }
  }

  private void parseSupFile(byte[] data, int offset, int length, OutputOptions outputOptions, Consumer<CuesWithTiming> output) {
    buffer.reset(data, /* limit= */ offset + length);
    buffer.setPosition(offset);
    cueBuilder.reset();
    PgsSupTiming timing = new PgsSupTiming(outputOptions, output);
    ArrayList<Cue> displaySetCues = new ArrayList<>();
    long displaySetStartTimeUs = C.TIME_UNSET;
    while (buffer.bytesLeft() >= SUP_SEGMENT_HEADER_SIZE) {
      if (!readSupSegment(supSegment)) {
        break;
      }
      displaySetStartTimeUs = getDisplaySetStartTimeUs(displaySetStartTimeUs, supSegment);
      if (supSegment.sectionType == SECTION_TYPE_END) {
        handleSupDisplaySetEnd(timing, displaySetCues, displaySetStartTimeUs);
        displaySetStartTimeUs = C.TIME_UNSET;
      } else {
        parsePresentationSection(supSegment.sectionType, supSegment.sectionLength);
      }
      buffer.setPosition(supSegment.nextSectionPosition);
    }
    timing.finish();
  }

  private void handleSupDisplaySetEnd(PgsSupTiming timing, ArrayList<Cue> displaySetCues, long displaySetStartTimeUs) {
    displaySetCues.clear();
    cueBuilder.build(displaySetCues);
    boolean isClearDisplaySet = cueBuilder.isClearDisplaySet();
    cueBuilder.clearPresentation();
    if (isClearDisplaySet) {
      timing.onClearDisplaySet(displaySetStartTimeUs);
    } else if (!displaySetCues.isEmpty()) {
      timing.onDisplaySet(displaySetCues, displaySetStartTimeUs);
    }
  }

  private boolean readSupSegment(PgsSupSegment segment) {
    if (buffer.readUnsignedShort() != SUP_SEGMENT_MAGIC) {
      segment.clear();
      return false;
    }
    long ptsUs = Util.scaleLargeTimestamp(buffer.readUnsignedInt(), C.MICROS_PER_SECOND, 90000);
    buffer.skipBytes(4);
    int sectionType = buffer.readUnsignedByte();
    int sectionLength = buffer.readUnsignedShort();
    int nextSectionPosition = buffer.getPosition() + sectionLength;
    if (nextSectionPosition > buffer.limit()) {
      buffer.setPosition(buffer.limit());
      segment.clear();
      return false;
    }
    segment.set(ptsUs, sectionType, sectionLength, nextSectionPosition);
    return true;
  }

  private void parsePresentationSection(int sectionType, int sectionLength) {
    switch (sectionType) {
      case SECTION_TYPE_PALETTE:
        cueBuilder.parsePaletteSection(buffer, sectionLength);
        break;
      case SECTION_TYPE_BITMAP_PICTURE:
        cueBuilder.parseBitmapSection(buffer, sectionLength);
        break;
      case SECTION_TYPE_IDENTIFIER:
        cueBuilder.parseIdentifierSection(buffer, sectionLength);
        break;
      case SECTION_TYPE_WINDOW_DEFINITION:
        cueBuilder.parseWindowDefinitionSection(buffer, sectionLength);
        break;
      default:
        break;
    }
  }
}
