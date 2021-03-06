package org.openstreetmap.josm.gradle.plugin.i18n.io

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@ExperimentalUnsignedTypes
class I18nReadWriteTest {

  val emptyTranslations: Map<MsgId, MsgStr> = mapOf(MsgId(MsgStr("")) to MsgStr("Content-Type: text/plain; charset=UTF-8"))
  val translations1: Map<MsgId, MsgStr> = mapOf(
    MsgId(MsgStr("")) to MsgStr("Sing\nSing2\nContent-Type: text/plain; charset=UTF-8"),
    MsgId(MsgStr("1", "2", "3")) to MsgStr("Sing"),
    MsgId(MsgStr("1", "2"), "context") to MsgStr("Singular", "Plural"),
    MsgId(MsgStr("Many plurals (253 is maximum of *.lang)", *(2..253).map { it.toString() }.toTypedArray())) to MsgStr("1", *(2..253).map { it.toString() }.toTypedArray()),
    MsgId(MsgStr("Emoji \uD83D\uDE0D", "\uD83C\uDDF1\uD83C\uDDFB"), "\uD83D\uDE39") to MsgStr("\uD83E\uDDB8\uD83C\uDFFF\u200D♂️", "\uD83C\uDFF3️\u200D\uD83C\uDF08", ""),
    MsgId(MsgStr("Umlaut äöüÄÖÜ")) to MsgStr("ẞß")
  )

  @Test
  fun testMoSerializationPersistence() {
    testSerializationPersistence(emptyTranslations, true)
    testSerializationPersistence(emptyTranslations, false)

    testSerializationPersistence(translations1, true)
    testSerializationPersistence(translations1, false)
  }

  private fun testSerializationPersistence(translations: Map<MsgId, MsgStr>, isBigEndian: Boolean) {
    val writeResult1 = ByteArrayOutputStream()
    MoWriter().writeStream(writeResult1, translations, isBigEndian)

    val readResult1 = MoReader(writeResult1.toByteArray()).readFile()
    val writeResult2 = ByteArrayOutputStream()
    MoWriter().writeStream(writeResult2, readResult1, isBigEndian)
    val readResult2 = MoReader(writeResult2.toByteArray()).readFile()

    assertEquals(translations, readResult1)
    assertEquals(readResult1, readResult2)
  }

  @Test
  fun testMoToLangAndBack() {
    testMoToLangAndBack(emptyTranslations)
    testMoToLangAndBack(translations1)
  }

  fun testMoToLangAndBack(translations: Map<MsgId, MsgStr>) {
    val moWriteResult1 = ByteArrayOutputStream()
    MoWriter().writeStream(moWriteResult1, translations, true)

    val moReadResult1 = MoReader(moWriteResult1.toByteArray()).readFile()
    // The empty string is not envisaged in the *.lang file format, so it is extracted here and put back into the result later
    val emptyElement = moReadResult1.keys.firstOrNull { it.toByteArray().isEmpty() }
    assertEquals(translations, moReadResult1)

    val langStreamOrig = ByteArrayOutputStream()
    LangWriter().writeLangStream(langStreamOrig, moReadResult1.minus(listOfNotNull(emptyElement).toTypedArray()).keys.toList(), mapOf(), true)
    val langStreamTrans = ByteArrayOutputStream()
    LangWriter().writeLangStream(langStreamTrans, moReadResult1.minus(listOfNotNull(emptyElement).toTypedArray()).keys.toList(), moReadResult1)

    val langReadResult = LangReader().readLangStreams("en", ByteArrayInputStream(langStreamOrig.toByteArray()), mapOf("es" to ByteArrayInputStream(langStreamTrans.toByteArray()))).get("es")
      // putting the empty element back into the result (if present)
      ?.plus(listOfNotNull(emptyElement).map { it to moReadResult1.get(it) })

    assertEquals(moReadResult1, langReadResult)
    assertNotNull(langReadResult)
    requireNotNull(langReadResult)

    val moWriteResult2 = ByteArrayOutputStream()
    MoWriter().writeStream(moWriteResult2, langReadResult.mapNotNull{ val value = it.value; if (value != null) it.key to value else null }.toMap(), false)

    val moReadResult2 = MoReader(moWriteResult2.toByteArray()).readFile()
    assertEquals(langReadResult, moReadResult2)
  }
}
