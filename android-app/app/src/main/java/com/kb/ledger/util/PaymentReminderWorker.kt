package com.kb.ledger.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kb.ledger.MainActivity
import com.kb.ledger.R
import com.kb.ledger.data.AppDatabase
import com.kb.ledger.data.model.TransactionType
import java.util.concurrent.TimeUnit

/**
 * Worker that verifies whether an outstanding balance still exists before triggering a notification.
 * Respects settlement, contact archiving, and privacy on the lock screen.
 */
class PaymentReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_PERSON_ID = "person_id"
        const val CHANNEL_ID = "ledger_reminders"
        const val CHANNEL_NAME = "Payment Reminders"
    }

    override suspend fun doWork(): Result {
        val personId = inputData.getLong(KEY_PERSON_ID, -1L)
        if (personId == -1L) return Result.success()

        val db = AppDatabase.getDatabase(applicationContext)
        val person = db.personDao().getPersonById(personId) ?: return Result.success()

        // Skip archived customers
        if (person.isArchived) return Result.success()

        // Check current balance
        val transactions = db.transactionDao().getTransactionsForPersonOnce(personId)
        val balanceCents = transactions.sumOf {
            if (it.type == TransactionType.RECEIVE) it.amountCents else -it.amountCents
        }

        // Only notify if customer still owes money (> 0)
        if (balanceCents <= 0) return Result.success()

        postReminderNotification(person.id, person.name)
        return Result.success()
    }

    private fun postReminderNotification(personId: Long, personName: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for scheduled payment follow-ups"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }
            notificationManager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_PERSON_ID", personId)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            personId.toInt(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Payment Follow-up Due")
            .setContentText("Reminder to follow up with $personName")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // Protect privacy on lock screen
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(personId.toInt(), notification)
    }
}

/**
 * Helper to schedule or cancel reminder workers using WorkManager.
 */
object PaymentReminderScheduler {

    fun scheduleReminder(context: Context, personId: Long, delayDays: Long) {
        val workRequest = OneTimeWorkRequestBuilder<PaymentReminderWorker>()
            .setInitialDelay(delayDays, TimeUnit.DAYS)
            .setInputData(workDataOf(PaymentReminderWorker.KEY_PERSON_ID to personId))
            .addTag("reminder_$personId")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "payment_reminder_$personId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun cancelReminder(context: Context, personId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("payment_reminder_$personId")
    }
}
