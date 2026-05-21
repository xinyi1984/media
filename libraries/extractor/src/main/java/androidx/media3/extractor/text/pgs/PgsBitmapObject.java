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

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;

/** Cached PGS bitmap object data for the current epoch. */
/* package */ final class PgsBitmapObject {

  private final ParsableByteArray bitmapData;
  int id;
  private int width;
  private int height;

  PgsBitmapObject() {
    bitmapData = new ParsableByteArray();
    reset();
  }

  boolean isComplete() {
    return width != 0
        && height != 0
        && bitmapData.limit() != 0
        && bitmapData.getPosition() == bitmapData.limit();
  }

  int width() {
    return width;
  }

  int height() {
    return height;
  }

  void resetReader(ParsableByteArray reader) {
    reader.reset(bitmapData.getData(), bitmapData.limit());
  }

  void resetForBaseSection(int width, int height, int rleDataLength) {
    this.width = width;
    this.height = height;
    bitmapData.reset(rleDataLength);
  }

  void resetData() {
    width = 0;
    height = 0;
    bitmapData.reset(0);
  }

  boolean appendRleData(ParsableByteArray buffer, int bytesToAppend) {
    int position = bitmapData.getPosition();
    int bytesLeft = bitmapData.limit() - position;
    if (bytesToAppend < 0 || bytesToAppend > bytesLeft) {
      return false;
    }
    if (bytesToAppend == 0) {
      return true;
    }
    buffer.readBytes(bitmapData.getData(), position, bytesToAppend);
    bitmapData.setPosition(position + bytesToAppend);
    return true;
  }

  void reset() {
    id = C.INDEX_UNSET;
    resetData();
  }
}
