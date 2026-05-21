/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.extractor.ts;

import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

@UnstableApi
public final class PgsReader implements ElementaryStreamReader {

  private static final int SECTION_TYPE_PALETTE = 0x14;
  private static final int SECTION_TYPE_BITMAP_PICTURE = 0x15;
  private static final int SECTION_TYPE_IDENTIFIER = 0x16;
  private static final int SECTION_TYPE_WINDOW_DEF = 0x17;
  private static final int SECTION_TYPE_END = 0x80;

  @Nullable
  private final String language;
  private final String containerMimeType;
  private @MonotonicNonNull TrackOutput output;

  private static final int STATE_EXPECT_NEXT = -1;
  private static final int STATE_SECTION_TYPE_READ = 0;
  private static final int STATE_SECTION_SIZE_FIRST_BYTE_READ = 1;
  private static final int STATE_SECTION_BYTES_COUNTDOWN = 2;

  private int stateOfReading;
  private int sectionType;
  private int sectionBytesToRead;
  private int firstByteOfSectionSize;
  private int sampleBytesWritten;
  private long sampleTimeUs;
  private long packetTimeUs;
  private boolean writingSample;

  public PgsReader(@Nullable String language, String containerMimeType) {
    sectionType = -1;
    sampleBytesWritten = 0;
    sectionBytesToRead = 0;
    sampleTimeUs = C.TIME_UNSET;
    packetTimeUs = C.TIME_UNSET;
    stateOfReading = STATE_EXPECT_NEXT;
    this.language = language;
    this.containerMimeType = containerMimeType;
  }

  @Override
  public void seek() {
    sectionType = -1;
    writingSample = false;
    sampleBytesWritten = 0;
    sectionBytesToRead = 0;
    sampleTimeUs = C.TIME_UNSET;
    packetTimeUs = C.TIME_UNSET;
    stateOfReading = STATE_EXPECT_NEXT;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_TEXT);
    output.format(
        new Format.Builder()
            .setId(idGenerator.getFormatId())
            .setContainerMimeType(containerMimeType)
            .setSampleMimeType(MimeTypes.APPLICATION_PGS)
            .setLanguage(language)
            .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
            .build());
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if ((flags & FLAG_DATA_ALIGNMENT_INDICATOR) == 0) {
      return;
    }
    writingSample = true;
    packetTimeUs = pesTimeUs;
    if (sampleTimeUs == C.TIME_UNSET) {
      sampleTimeUs = pesTimeUs;
    }
  }

  @Override
  public void consume(ParsableByteArray data) {
    if (!writingSample) {
      return;
    }
    while (data.bytesLeft() > 0) {
      int chunkStart = data.getPosition();
      boolean sampleFinished = readUntilEndOfDisplaySet(data);
      int chunkEnd = data.getPosition();
      data.setPosition(chunkStart);
      appendSampleData(data, chunkEnd - chunkStart);
      if (sampleFinished) {
        if (sampleTimeUs != C.TIME_UNSET) {
          commitSample();
        } else {
          resetSampleState();
        }
      }
      data.setPosition(chunkEnd);
    }
  }

  private void appendSampleData(ParsableByteArray data, int bytesToWrite) {
    if (sampleTimeUs == C.TIME_UNSET) {
      sampleTimeUs = packetTimeUs;
    }
    output.sampleData(data, bytesToWrite);
    sampleBytesWritten += bytesToWrite;
  }

  private void commitSample() {
    output.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleBytesWritten, 0, null);
    resetSampleState();
  }

  private void resetSampleState() {
    writingSample = false;
    sampleBytesWritten = 0;
    sampleTimeUs = C.TIME_UNSET;
  }

  private boolean readUntilEndOfDisplaySet(ParsableByteArray array) {
    byte[] buffer = array.getData();
    int position = array.getPosition();
    int limit = array.limit();
    while (limit - position > 0) {
      int b = buffer[position++] & 0xff;
      switch (stateOfReading) {
        case STATE_EXPECT_NEXT:
          if (isSectionType(b)) {
            sectionType = b;
            stateOfReading = STATE_SECTION_TYPE_READ;
          }
          break;
        case STATE_SECTION_TYPE_READ:
          firstByteOfSectionSize = b;
          stateOfReading = STATE_SECTION_SIZE_FIRST_BYTE_READ;
          break;
        case STATE_SECTION_SIZE_FIRST_BYTE_READ:
          sectionBytesToRead = firstByteOfSectionSize << 8 | b;
          stateOfReading = sectionBytesToRead == 0 ? STATE_EXPECT_NEXT : STATE_SECTION_BYTES_COUNTDOWN;
          if (stateOfReading == STATE_EXPECT_NEXT && sectionType == SECTION_TYPE_END) {
            array.setPosition(position);
            return true;
          }
          break;
        case STATE_SECTION_BYTES_COUNTDOWN:
          sectionBytesToRead--;
          int bytesToRead = Math.min(sectionBytesToRead, limit - position);
          position += bytesToRead;
          sectionBytesToRead -= bytesToRead;
          if (sectionBytesToRead == 0) {
            stateOfReading = STATE_EXPECT_NEXT;
            if (sectionType == SECTION_TYPE_END) {
              array.setPosition(position);
              return true;
            }
          }
          break;
      }
    }
    array.setPosition(position);
    return false;
  }

  private static boolean isSectionType(int value) {
    return value == SECTION_TYPE_IDENTIFIER
        || value == SECTION_TYPE_WINDOW_DEF
        || value == SECTION_TYPE_PALETTE
        || value == SECTION_TYPE_BITMAP_PICTURE
        || value == SECTION_TYPE_END;
  }
}
