package dev.sn.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.sn.app.MainActivity

/**
 * Home screen shortcut.
 *
 * Deliberately a launcher rather than a chat surface: anything the agent does
 * that needs confirming needs a real screen to confirm it on, so tapping this
 * opens the app rather than starting a turn in the background.
 */
class SnWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetBody()
            }
        }
    }

    @Composable
    private fun WidgetBody() {
        val open = Intent(
            androidx.glance.LocalContext.current,
            MainActivity::class.java,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(20.dp)
                .padding(12.dp)
                .clickable(actionStartActivity(open)),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Text(
                text = "Ask sn",
                style = TextStyle(
                    color = GlanceTheme.colors.onSecondaryContainer,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

class SnWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SnWidget()
}
