package org.openstreetmap.josm.gradle.plugin.i18n.io

import java.io.OutputStream
import java.util.Comparator

@ExperimentalUnsignedTypes
class MoWriter {
  /**
   * Writes the (low-endian) *.mo file to the given output stream
   */
  fun writeStream(stream: OutputStream, translations: Map<MsgId, MsgStr>, isBigEndian: Boolean) {
    // offset 0: magic number
    val magicBytes = MoReader.BE_MAGIC.let { if (isBigEndian) it else it.reversedArray() }

    val sortedOriginalMsgIds = translations
      .toSortedMap(Comparator { o1, o2 -> String(o1.toByteArray()).compareTo(String(o2.toByteArray())) })

    val numStrings = sortedOriginalMsgIds.size.toUInt()
    val stringsOffset = MoReader.HEADER_SIZE_IN_BYTES
    val translationStringsOffset = stringsOffset + numStrings * 8u
    val hashTableOffset = translationStringsOffset + numStrings * 8u

    val header = listOf(
      // offset 4: file format revision
      FourBytes(0u, isBigEndian),
      // offset 8: number of strings
      FourBytes(numStrings, isBigEndian),
      // offset 12: offset of table with original strings
      FourBytes(stringsOffset, isBigEndian),
      // offset 16: offset of table with translation strings
      FourBytes(translationStringsOffset, isBigEndian),
      // offset 20: size of hashing table
      FourBytes(0u, isBigEndian),
      // offset 24: offset of hashing table
      FourBytes(hashTableOffset, isBigEndian)
    ).toByteArray()
    require((header.size + magicBytes.size).toUInt() == stringsOffset) // make sure the string offset is set correctly

    stream.write(magicBytes)
    stream.write(header)

    var offset = hashTableOffset

    // Write offsets of original strings
    sortedOriginalMsgIds.forEach {
      val numBytes = it.key.toByteArray().size.toUInt()
      stream.write(listOf(FourBytes(numBytes, isBigEndian)).toByteArray())
      stream.write(listOf(FourBytes(offset, isBigEndian)).toByteArray())
      offset += numBytes + 1u
    }

    // Write offsets of translated strings
    sortedOriginalMsgIds.forEach {
      val numBytes = it.value.toByteArray().size.toUInt()
      stream.write(listOf(FourBytes(numBytes, isBigEndian)).toByteArray())
      stream.write(listOf(FourBytes(offset, isBigEndian)).toByteArray())
      offset += numBytes + 1u
    }

    // Write original strings
    sortedOriginalMsgIds.forEach {
      stream.write(it.key.toByteArray())
      stream.write(0x00)
    }

    // Write translated strings
    sortedOriginalMsgIds.forEach {
      stream.write(it.value.toByteArray())
      stream.write(0x00)
    }
  }
}
