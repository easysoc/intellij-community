// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.updates

import com.intellij.openapi.updateSettings.impl.ChannelStatus
import com.intellij.openapi.updateSettings.impl.UpdateChannel
import com.intellij.openapi.updateSettings.impl.UpdatesInfo
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BareTestFixtureTestCase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.net.URL
import java.text.SimpleDateFormat

class UpdateInfoParsingTest : BareTestFixtureTestCase() {
  @Test fun liveJetBrainsUpdateFile() {
    try {
      val info = load(URL("https://www.jetbrains.com/updates/updates.xml").readText())
      assertNotNull(info["IC"])
    }
    catch (e: IOException) {
      assumeTrue(e.toString(), false)
    }
  }

  @Test fun liveAndroidUpdateFile() {
    try {
      val info = load(URL("https://dl.google.com/android/studio/patches/updates.xml").readText())
      assertNotNull(info["AI"])
    }
    catch (e: IOException) {
      assumeTrue(e.toString(), false)
    }
  }

  @Test fun emptyChannels() {
    val info = load("""
      <products>
        <product name="IntelliJ IDEA">
          <code>IU</code>
          <code>IC</code>
        </product>
      </products>""".trimIndent())
    val product = info["IU"]!!
    assertEquals("IntelliJ IDEA", product.name)
    assertEquals(0, product.channels.size)
    assertEquals(product, info["IC"])
  }

  @Test fun oneProductOnly() {
    val info = load("""
      <products>
        <product name="IntelliJ IDEA">
          <code>IU</code>

          <channel id="idea90" name="IntelliJ IDEA 9 updates" status="release" url="https://www.jetbrains.com/idea/whatsnew">
            <build number="95.627" version="9.0.4">
              <message>IntelliJ IDEA 9.0.4 is available. Please visit https://www.jetbrains.com/idea to learn more and download it.</message>
              <patch from="95.429" size="2"/>
            </build>
          </channel>

          <channel id="IDEA10EAP" name="IntelliJ IDEA X EAP" status="eap" licensing="eap" url="http://confluence.jetbrains.net/display/IDEADEV/IDEA+X+EAP">
            <build number="98.520" version="10" releaseDate="20110403">
              <message>IntelliJ IDEA X RC is available. Please visit http://confluence.jetbrains.net/display/IDEADEV/IDEA+X+EAP to learn more.</message>
              <button name="Download" url="http://www.jetbrains.com/idea" download="true"/>
            </build>
          </channel>
        </product>
      </products>""".trimIndent())

    val product = info["IU"]!!
    assertEquals("IntelliJ IDEA", product.name)
    assertEquals(2, product.channels.size)

    val channel = product.channels.find { it.id == "IDEA10EAP" }!!
    assertEquals(ChannelStatus.EAP, channel.status)
    assertEquals(UpdateChannel.Licensing.EAP, channel.licensing)
    assertEquals(1, channel.builds.size)

    val build = channel.builds[0]
    assertEquals("98.520", build.number.asStringWithoutProductCode())
    assertEquals("2011-04-03", SimpleDateFormat("yyyy-MM-dd").format(build.releaseDate))
    assertNotNull(build.downloadUrl)
    assertEquals(0, build.patches.size)

    assertEquals(1, product.channels.find { it.id == "idea90" }!!.builds[0].patches.size)
  }

  @Test fun targetRanges() {
    val info = load("""
      <products>
        <product name="IntelliJ IDEA">
          <code>IU</code>
          <channel id="IDEA_EAP" status="eap">
            <build number="2016.2.123" version="2016.2" targetSince="0" targetUntil="145.*"/>
            <build number="2016.2.123" version="2016.2" targetSince="2016.1" targetUntil="2016.1.*"/>
            <build number="2016.1.11" version="2016.1"/>
          </channel>
        </product>
      </products>""".trimIndent())
    assertEquals(2, info["IU"]!!.channels[0].builds.count { it.target != null })
  }

  @Test fun fullBuildNumbers() {
    val info = load("""
      <products>
        <product name="IntelliJ IDEA">
          <code>IU</code>
          <channel id="IDEA_EAP" status="eap">
            <build number="162.100" fullNumber="162.100.1" version="2016.2">
               <patch from="162.99" fullFrom="162.99.2" size="1"/>
            </build>
          </channel>
        </product>
      </products>""".trimIndent())
    val buildInfo = info["IU"]!!.channels[0].builds[0]
    assertEquals("162.100.1", buildInfo.number.asStringWithoutProductCode())
    assertEquals("162.99.2", buildInfo.patches[0].fromBuild.asStringWithoutProductCode())
  }

  private fun load(text: String) = UpdatesInfo(JDOMUtil.load(text))

  @Test
  fun disableMachineId() {
    val product = load("""
      <products>
        <product name="IntelliJ IDEA" disableMachineId="true">
          <code>IU</code>
          <channel id="IDEA_EAP" status="eap">
            <build number="162.100" fullNumber="162.100.1" version="2016.2">
               <patch from="162.99" fullFrom="162.99.2" size="1"/>
            </build>
          </channel>
        </product>
      </products>""".trimIndent())["IU"]!!
    assertTrue(product.disableMachineId)
  }
}
