/*
 * Copyright (C) 2020 Marijn Suijten
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

package com.sony.opentelephony.modemconfig

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.Log
import com.sony.opentelephony.modemconfig.SomcModemProperties
import org.xmlpull.v1.XmlPullParser
import java.nio.file.Files
import java.nio.file.Paths

private const val TAG = "ModemConfigReceiver"
private const val VERBOSE = false
private const val NOTIFICATION_CHANNEL_ID = "Configuration"
private const val NOTIFICATION_GROUP_KEY_SLOTS = "com.sony.opentelephony.modemconfig.slot_result"
private const val NOTIFICATION_ID = 1
private const val NOTIFICATION_ID_SLOT_BASE = 1000

private val newlineMatch = Regex("[\n\r]")

class ModemConfigService : Service() {
    private class ProviderFilter(val sim_config_id: String,
                                 val mcc: String? = null,
                                 val mnc: String? = null,
                                 val gid1: String? = null,
                                 val imsi: Regex? = null,
                                 val sp: Regex? = null
    ) {
        override fun toString() = "[mcc: $mcc, mnc: $mnc, gid1: $gid1, imsi: ${imsi?.pattern}, " +
                                  "sp: ${sp?.pattern}] => $sim_config_id"

        fun specificity() = listOfNotNull(mcc, mnc, gid1, imsi, sp).count()
    }

    private var providers: List<ProviderFilter> = emptyList()

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
        ?: throw Exception("Expected NotificationManager, got null")
    }

    private fun getProviders(context: Context): List<ProviderFilter> {
        if (providers.isNotEmpty()) return providers

        val list = arrayListOf<ProviderFilter>()

        val currentMap = hashMapOf<String, String>()
        var simConfigId: String? = null

        context.resources.getXml(R.xml.service_provider_sim_configs).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.END_TAG &&
                    xml.name == "service_provider_sim_config") {
                    // When a node is closed, collect all elements and create a filter entry:
                    list.add(ProviderFilter(
                            simConfigId!!,
                            currentMap["mcc"],
                            currentMap["mnc"],
                            currentMap["gid1"],
                            currentMap["imsi"]?.let { Regex(it) },
                            currentMap["sp"]?.let { Regex(it) }
                    ))
                    currentMap.clear()
                    continue
                }

                if (xml.eventType != XmlPullParser.START_TAG)
                    continue

                if (xml.name == "service_provider_sim_config") {
                    // Start collection for this config id:
                    simConfigId = xml.getAttributeValue(null, "sim_config_id")
                    if (TextUtils.isEmpty(simConfigId))
                        throw Exception("Unexpected empty sim_config_id attribute!")
                    currentMap.clear()
                } else if (simConfigId != null) {
                    // If not in a service_provider_sim_config element, and a valid simConfigId
                    // /exists parse all members/subelements:
                    val text = xml.nextText().cleanString()
                    if (currentMap.containsKey(xml.name))
                        throw Exception("Already parsed ${xml.name}!")
                    currentMap[xml.name] = text
                }
            }
        }

        list.sortByDescending { it.specificity() }
        providers = list
        return list
    }

    private fun String.cleanString() = replace(newlineMatch, "").trim()

    private fun findConfigurationName(context: Context, tm: TelephonyManager): String? {
        val operator = tm.simOperator
        if (operator.isNullOrEmpty()) {
            Log.d(TAG, "Operator is null or empty")
            return null
        }
        val mcc = operator.take(3)
        val mnc = operator.drop(3)

        val sp = tm.simOperatorName?.cleanString() ?: ""
        val gid1 = tm.groupIdLevel1 ?: ""
        val imsi = tm.subscriberId
        if (imsi.isNullOrEmpty()) {
            Log.d(TAG, "Imsi is null or empty")
            return null
        }

        if (VERBOSE) Log.v(TAG, "Matching providers against: $sp $mcc/$mnc, imsi: $imsi" +
                                ", gid: $gid1")

        val result = getProviders(context).firstOrNull f@{ info ->
            if (info.mcc != null && info.mcc != mcc)
                return@f false
            if (info.mnc != null && info.mnc != mnc)
                return@f false

            if (info.gid1 != null && !gid1.startsWith(info.gid1))
                return@f false

            if (info.imsi != null && !info.imsi.matches(imsi))
                return@f false

            if (info.sp != null && !info.sp.matches(sp))
                return@f false

            true
        }

        return if (result == null) {
            Log.w(TAG, "No matching config found, falling back to generic IMS")
            "S9999.9"
        } else {
            Log.i(TAG, "Matched with $result")
            result.sim_config_id
        }
    }

    private fun findFirmwarePath(configId: String): String {
        val conf = Paths.get(resources.getString(R.string.modem_config_path), configId,
                             resources.getString(R.string.modem_config_name))
        return try {
            if (Files.isRegularFile(conf)) {
                Files.readAllLines(conf).first()
            } else {
                // TODO: Can/should we read default from modem-switcher prop?
                "default"
            }
        } catch (e: Exception) {
            "failed to derive"
        }
    }

    private fun handleSubscription(sub: SubscriptionInfo) {
        if (VERBOSE) Log.v(TAG, "Checking sub $sub")

        val globalTm = getSystemService(TelephonyManager::class.java)
        val tm = globalTm!!.createForSubscriptionId(sub.subscriptionId)
        val name = findConfigurationName(this, tm)

        val notificationText = if (name != null) {
            val prop = "persist.somc.cust.modem${sub.simSlotIndex}"
            Log.d(TAG, "Setting $prop to $name")
            when (sub.simSlotIndex) {
                0 -> SomcModemProperties.cust_modem_0(name)
                1 -> SomcModemProperties.cust_modem_1(name)
                else -> throw Exception("Unknown slotIdx ${sub.simSlotIndex}")
            }

            resources.getString(
                    R.string.notification_text_modem_configuration_resolved_modem_config,
                    name,
                    findFirmwarePath(name))
        } else {
            resources.getString(R.string.notification_text_modem_configuration_no_match)
        }

        val notification = Notification.Builder(this@ModemConfigService,
                                                NOTIFICATION_CHANNEL_ID)
                .run {
                    setSmallIcon(R.drawable.ic_sim_card)
                    setContentTitle(resources.getString(
                            R.string.notification_title_slot_index,
                            sub.simSlotIndex))
                    setContentText(notificationText.substringBefore('\n'))
                    setGroup(NOTIFICATION_GROUP_KEY_SLOTS)
                    setSortKey(sub.simSlotIndex.toString())
                    style = Notification.BigTextStyle()
                            .bigText(notificationText)
                    build()
                }

        notificationManager.notify(NOTIFICATION_ID_SLOT_BASE + sub.simSlotIndex, notification)
    }

    override fun onCreate() {
        Log.e(TAG, "Starting")
        val sm = getSystemService(SubscriptionManager::class.java)
                 ?: throw Exception("Expected SubscriptionManager, got null")

        val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                resources.getString(R.string.notification_channel_configuration_info),
                NotificationManager.IMPORTANCE_MIN)
        notificationManager.createNotificationChannel(channel)

        // Our callback is invoked once on .add too; no need to run the contents manually at startup
        sm.addOnSubscriptionsChangedListener(
                object : SubscriptionManager.OnSubscriptionsChangedListener() {
                    // Even though this callback is invoked a lot (when sims are changed), apply no
                    // caching at all. The modem-switcher itself makes sure to not needlessly flash
                    // firmware - let it handle the validation.
                    override fun onSubscriptionsChanged() {
                        sm.activeSubscriptionInfoList?.run {
                            if (any()) {
                                val summaryNotification = Notification.Builder(
                                        this@ModemConfigService,
                                        NOTIFICATION_CHANNEL_ID)
                                        .run {
                                            setSmallIcon(R.drawable.ic_sim_card)
                                            setContentTitle(resources.getString(
                                                    R.string.notification_title_modem_configuration
                                            ))
                                            setGroup(NOTIFICATION_GROUP_KEY_SLOTS)
                                            setGroupSummary(true)
                                            build()
                                        }

                                notificationManager.notify(NOTIFICATION_ID, summaryNotification)
                            }

                            forEach(::handleSubscription)
                        }

                    }
                })
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
