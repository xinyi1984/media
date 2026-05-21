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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

@UnstableApi
public final class DvdLpcmReader implements ElementaryStreamReader {

  private static final int AUDIO_HEADER_SIZE = 3;
  private static final int SAMPLES_PER_PACKED_GROUP = 4;
  private static final int MONO_SAMPLES_PER_PACKED_GROUP = 2;
  private static final int DECODED_BYTES_PER_SAMPLE = 3;
  private static final int UNSUPPORTED_BITS_PER_SAMPLE = 28;
  private static final int[] SAMPLE_RATES = {48000, 96000, 44100, 32000};

  @Nullable
  private final String language;

  @Nullable
  private String formatId;
  private @MonotonicNonNull TrackOutput output;

  private long timeUs;
  private boolean formatSet;
  private int sampleBytesWritten;
  private int channelCount;
  private int sampleRate;
  private int bitsPerSample;
  private int blockSize;
  private int packedGroupsPerBlock;
  private byte[] pendingBlock = new byte[0];
  private byte[] decodedBlock = new byte[0];
  private final ParsableByteArray decodedBlockData = new ParsableByteArray();
  private int pendingBlockBytes;

  public DvdLpcmReader(@Nullable String language) {
    this.language = language;
    timeUs = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    formatSet = false;
    timeUs = C.TIME_UNSET;
    pendingBlockBytes = 0;
    sampleBytesWritten = 0;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    timeUs = pesTimeUs;
    sampleBytesWritten = 0;
  }

  @Override
  public void consume(ParsableByteArray data) {
    TrackOutput output = this.output;
    if (output == null || timeUs == C.TIME_UNSET) {
      return;
    }
    if (data.bytesLeft() < AUDIO_HEADER_SIZE) {
      return;
    }
    data.skipBytes(1);
    int paramByte = data.readUnsignedByte();
    data.skipBytes(1);
    int quantCode = (paramByte >> 6) & 0x03;
    int rateCode = (paramByte >> 4) & 0x03;
    int parsedChannelCount = (paramByte & 0x07) + 1;
    int parsedSampleRate = SAMPLE_RATES[rateCode];
    int parsedBitsPerSample = 16 + quantCode * 4;
    if (parsedBitsPerSample == UNSUPPORTED_BITS_PER_SAMPLE) {
      return;
    }
    boolean formatChanged = !formatSet || channelCount != parsedChannelCount || sampleRate != parsedSampleRate || bitsPerSample != parsedBitsPerSample;
    channelCount = parsedChannelCount;
    sampleRate = parsedSampleRate;
    bitsPerSample = parsedBitsPerSample;
    configureBlockLayout();
    if (formatChanged) {
      formatSet = true;
      output.format(
          new Format.Builder()
              .setId(formatId)
              .setSampleMimeType(MimeTypes.AUDIO_RAW)
              .setLanguage(language)
              .setChannelCount(channelCount)
              .setSampleRate(sampleRate)
              .setPcmEncoding(pcmEncodingForBitsPerSample(bitsPerSample))
              .build());
      pendingBlockBytes = 0;
    }
    if (bitsPerSample == 16) {
      int avail = data.bytesLeft();
      output.sampleData(data, avail);
      sampleBytesWritten += avail;
    } else {
      decodePackedSamples(data, output);
    }
  }

  @Override
  public void packetFinished() {
    TrackOutput output = this.output;
    if (output == null || sampleBytesWritten == 0 || timeUs == C.TIME_UNSET) {
      return;
    }
    output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, sampleBytesWritten, 0, null);
    sampleBytesWritten = 0;
    timeUs = C.TIME_UNSET;
  }

  private void configureBlockLayout() {
    int samplesPerBlock;
    if (bitsPerSample == 16) {
      blockSize = channelCount * 2;
      packedGroupsPerBlock = 1;
      return;
    }
    switch (channelCount) {
      case 1:
        blockSize = SAMPLES_PER_PACKED_GROUP * bitsPerSample / 8;
        samplesPerBlock = SAMPLES_PER_PACKED_GROUP;
        packedGroupsPerBlock = 2;
        break;
      case 2:
      case 4:
        blockSize = SAMPLES_PER_PACKED_GROUP * bitsPerSample / 8;
        samplesPerBlock = SAMPLES_PER_PACKED_GROUP / channelCount;
        packedGroupsPerBlock = 1;
        break;
      case 8:
        blockSize = channelCount * bitsPerSample / 8;
        samplesPerBlock = 1;
        packedGroupsPerBlock = 2;
        break;
      default:
        blockSize = SAMPLES_PER_PACKED_GROUP * channelCount * bitsPerSample / 8;
        samplesPerBlock = SAMPLES_PER_PACKED_GROUP;
        packedGroupsPerBlock = channelCount;
        break;
    }
    if (pendingBlock.length < blockSize) {
      pendingBlock = new byte[blockSize];
    }
    int decodedBlockSize = samplesPerBlock * channelCount * DECODED_BYTES_PER_SAMPLE;
    if (decodedBlock.length < decodedBlockSize) {
      decodedBlock = new byte[decodedBlockSize];
    }
  }

  private static int pcmEncodingForBitsPerSample(int bitsPerSample) {
    return bitsPerSample == 16 ? C.ENCODING_PCM_16BIT_BIG_ENDIAN : C.ENCODING_PCM_24BIT_BIG_ENDIAN;
  }

  private void decodePackedSamples(ParsableByteArray data, TrackOutput output) {
    while (data.bytesLeft() > 0) {
      int needed = blockSize - pendingBlockBytes;
      int copy = Math.min(needed, data.bytesLeft());
      data.readBytes(pendingBlock, pendingBlockBytes, copy);
      pendingBlockBytes += copy;
      if (pendingBlockBytes == blockSize) {
        int decodedLength = decodeBlock(pendingBlock, decodedBlock);
        decodedBlockData.reset(decodedBlock, decodedLength);
        output.sampleData(decodedBlockData, decodedLength);
        sampleBytesWritten += decodedLength;
        pendingBlockBytes = 0;
      }
    }
  }

  private int decodeBlock(byte[] src, byte[] decoded) {
    int in = 0;
    int out = 0;
    int sampleCount = channelCount == 1 ? MONO_SAMPLES_PER_PACKED_GROUP : SAMPLES_PER_PACKED_GROUP;
    int packedGroupSize = bitsPerSample == 20 ? sampleCount * 5 / 2 : sampleCount * 3;
    for (int i = 0; i < packedGroupsPerBlock; i++) {
      out = decodePackedGroup(src, in, decoded, out, sampleCount);
      in += packedGroupSize;
    }
    return out;
  }

  private int decodePackedGroup(byte[] src, int in, byte[] dst, int out, int sampleCount) {
    int extraOffset = in + sampleCount * 2;
    if (bitsPerSample == 20) {
      for (int i = 0; i < sampleCount; i += 2) {
        int packed = src[extraOffset++] & 0xFF;
        dst[out++] = src[in + i * 2];
        dst[out++] = src[in + i * 2 + 1];
        dst[out++] = (byte) (packed & 0xF0);
        dst[out++] = src[in + i * 2 + 2];
        dst[out++] = src[in + i * 2 + 3];
        dst[out++] = (byte) ((packed & 0x0F) << 4);
      }
    } else {
      for (int i = 0; i < sampleCount; i++) {
        dst[out++] = src[in + i * 2];
        dst[out++] = src[in + i * 2 + 1];
        dst[out++] = src[extraOffset++];
      }
    }
    return out;
  }
}
