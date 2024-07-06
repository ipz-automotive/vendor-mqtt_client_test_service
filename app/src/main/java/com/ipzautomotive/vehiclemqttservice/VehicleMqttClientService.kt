package com.ipzautomotive.vehiclemqttservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.IBinder
import android.os.PowerManager
import info.mqtt.android.service.MqttAndroidClient
import info.mqtt.android.service.QoS
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Timer
import kotlin.concurrent.timerTask

class VehicleMqttClientService : Service() {
    private lateinit var mMqttAndroidClient: MqttAndroidClient
    private var mWakeLock: PowerManager.WakeLock? = null
    private var mIsStarted = false
    private var mIsConnected = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initService();

        return START_STICKY
    }

    private fun startClientConnection() {
        CLIENT_ID += System.currentTimeMillis()
        mMqttAndroidClient = MqttAndroidClient(applicationContext, SERVER_URI, CLIENT_ID)
        mMqttAndroidClient.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                if (reconnect) {
                    Log.d(LOG_TAG, "reconnected to: $SERVER_URI")
                    // Because Clean Session is true, we need to re-subscribe
                    subscribeToTopics()
                } else {
                    Log.d(LOG_TAG, "connected to: $SERVER_URI")
                }
            }

            override fun connectionLost(cause: Throwable?) {
                Log.d(LOG_TAG, "connection lost: $SERVER_URI")
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                Log.d(LOG_TAG, "topic: $topic, message: ${String(message.payload)}")
                // if speed -> hal.setfloatprop(speed, speed)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken) {}
        })

        connectClient()
    }

    private fun connectClient() {
        val mqttConnectOptions = MqttConnectOptions()
        mqttConnectOptions.isAutomaticReconnect = true
        mqttConnectOptions.isCleanSession = false
        Log.d(LOG_TAG, "connecting to: $SERVER_URI")
        mMqttAndroidClient.connect(mqttConnectOptions, null,
            object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    val disconnectedBufferOptions = DisconnectedBufferOptions()
                    disconnectedBufferOptions.isBufferEnabled = true
                    disconnectedBufferOptions.bufferSize = 100
                    disconnectedBufferOptions.isPersistBuffer = false
                    disconnectedBufferOptions.isDeleteOldestMessages = false
                    mIsConnected = true
                    mMqttAndroidClient.setBufferOpts(disconnectedBufferOptions)
                    subscribeToTopics()
                    publishMessage()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    //addToHistory("Failed to connect: $serverUri")
                    Log.d(LOG_TAG, "Failed to connect $SERVER_URI, retrying in 10seconds...")
                }
            })
    }

    private fun initService() {
        if (!mIsStarted) {
            Log.d(LOG_TAG, "Starting the foreground service task")
            mIsStarted = true

            // we need this lock so our service gets not affected by Doze Mode
            mWakeLock =
                (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                    newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VehicleMqttClientService::lock").apply {
                        acquire()
                    }
                }

            GlobalScope.launch(Dispatchers.IO) {
                delay(30 * 1000 )
                startClientConnection();
                while (mIsStarted) {
                    if (mIsConnected) {
//                        publishMessages()
                        for (i: Int in 240..300) {
                            for (topic in subscriptionTopics) {
                                val message = MqttMessage()
                                if (topic == "rpm") message.payload = ((i * 1000 + 5 * i) % 4000).toString().toByteArray()
                                else message.payload = i.toString().toByteArray()
                                if (mMqttAndroidClient.isConnected) {
                                    mMqttAndroidClient.publish(topic, message)
                                    delay(10)
                                }
                            }
                        }
                        for (i: Int in 240 downTo 1) {
                            for (topic in subscriptionTopics) {
                                val message = MqttMessage()
                                if (topic == "rpm") message.payload =
                                    ((i * 1000 + 5 * i) % 4000).toString().toByteArray()
                                else message.payload = i.toString().toByteArray()
                                if (mMqttAndroidClient.isConnected) {
                                    mMqttAndroidClient.publish(topic, message)
                                    delay(10)
                                }
                            }
                        }
                        continue
                    }
                    connectClient()
                    delay(10 * 1000)
                }
                Log.d(LOG_TAG, "End of the loop for the service")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(LOG_TAG, "The service has been created")
        val notification = createNotification()
        startForeground(1, notification)
    }

    fun subscribeToTopics() {
        for (topic in subscriptionTopics) {
            mMqttAndroidClient.subscribe(topic, QoS.AtMostOnce.value, null,
                    object : IMqttActionListener {

                override fun onSuccess(asyncActionToken: IMqttToken) {
                    Log.d(LOG_TAG, "subscribed to: $topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d(LOG_TAG, "cant subscribe to: $topic")
                }
            })
        }

    }

    private fun publishMessage() {
        val message = MqttMessage()
        message.payload = publishMessage.toByteArray()
        if (mMqttAndroidClient.isConnected) {
            mMqttAndroidClient.publish(publishTopic, message)
            //addToHistory("Message Published >$publishMessage<")
            if (!mMqttAndroidClient.isConnected) {
                //addToHistory(mMqttAndroidClient.bufferedMessageCount.toString() + " messages in buffer.")
            }
        } else {
            //Snackbar.make(findViewById(android.R.id.content), "Not connected", Snackbar.LENGTH_SHORT).setAction("Action", null).show()
//            mMqttAndroidClient.connect();
        }
    }

    private fun publishMessages() {
        for (i: Int in 1..300) {
            for (topic in subscriptionTopics) {
                val message = MqttMessage()
                if (topic == "rpm") message.payload = (i * 10).toString().toByteArray()
                else message.payload = i.toString().toByteArray()
                if (mMqttAndroidClient.isConnected) {
                    mMqttAndroidClient.publish(topic, message)
                }
            }
        }
        for (i: Int in 300 downTo 1) {
            for (topic in subscriptionTopics) {
                val message = MqttMessage()
                if (topic == "rpm") message.payload = (i * 10).toString().toByteArray()
                else message.payload = i.toString().toByteArray()
                if (mMqttAndroidClient.isConnected) {
                    mMqttAndroidClient.publish(topic, message)
                }
            }
        }
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "VehicleMqttClientChannel"

        // depending on the Android API that we're dealing with we will have
        // to use a specific method to create the notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            notificationChannelId,
            "VehicleMqttClientService notifications channel",
            NotificationManager.IMPORTANCE_HIGH
        ).let {
            it.description = "VehicleMqttClientService channel"
            it.enableLights(true)
            it.lightColor = Color.RED
            it.enableVibration(true)
            it.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
            it
        }
        notificationManager.createNotificationChannel(channel)

        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        }

        val builder: Notification.Builder = Notification.Builder(
            this,
            notificationChannelId)

        return builder
            .setContentTitle("VehicleMqttClient")
            .setContentText("VehicleMqttClient is working")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setTicker("Ticker text")
            .build()
    }

    override fun onDestroy() {
        mMqttAndroidClient.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        TODO("Not yet implemented")
    }


    companion object {
        private const val SERVER_URI = "tcp://21.37.14.88:1883"
        private val subscriptionTopics = listOf("speed", "rpm", "odo")
        private const val publishTopic = "test_topic"
        private const val publishMessage = "ABCDEFGH"
        private var CLIENT_ID = "VehicleMqttClient"
        private const val LOG_TAG = "VehicleMqttClientService"
    }
}