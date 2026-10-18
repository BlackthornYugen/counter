package dev.jskw.counter.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CounterWearApp()
            }
        }
    }
}

@Composable
fun CounterWearApp() {
    val context = LocalContext.current
    val counters by WearCounterSync.countersState.collectAsStateWithLifecycle()
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    DisposableEffect(context) {
        val listener = DataClient.OnDataChangedListener { dataEvents ->
            for (i in 0 until dataEvents.count) {
                val event = dataEvents.get(i)
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WearCounterSync.COUNTERS_PATH) {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val jsonStr = dataMap.getString(WearCounterSync.KEY_COUNTERS_JSON)
                    if (!jsonStr.isNullOrEmpty()) {
                        WearCounterSync.updateCountersFromJson(jsonStr)
                    }
                }
            }
        }
        WearCounterSync.registerDataClientListener(context, listener)
        onDispose {
            WearCounterSync.unregisterDataClientListener(context, listener)
        }
    }

    AppScaffold {
        ScreenScaffold(scrollState = columnState) { contentPadding ->
            TransformingLazyColumn(
                state = columnState,
                contentPadding = contentPadding
            ) {
                if (counters.isEmpty()) {
                    item {
                        ListHeader(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) {
                            Text(
                                text = "Counters",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    item {
                        Card(
                            onClick = { },
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) {
                            Text(
                                text = "No counters on phone",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    item {
                        Button(
                            onClick = {
                                WearCounterSync.sendActionFromWatch(
                                    context = context,
                                    action = WearCounterSync.ACTION_CREATE,
                                    title = "default watch counter"
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) {
                            Text(
                                text = "+ Create Counter",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    counters.forEach { counter ->
                        item(key = "header_${counter.id}") {
                            ListHeader(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .transformedHeight(this, transformationSpec),
                                transformation = SurfaceTransformation(transformationSpec)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (counter.isPublished) {
                                        Icon(
                                            imageVector = Icons.Default.Cloud,
                                            contentDescription = "Synced online"
                                        )
                                    }
                                    Text(
                                        text = counter.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        item(key = "card_${counter.id}") {
                            Card(
                                onClick = {
                                    WearCounterSync.sendActionFromWatch(
                                        context = context,
                                        action = WearCounterSync.ACTION_INCREMENT,
                                        counterId = counter.id
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .transformedHeight(this, transformationSpec),
                                transformation = SurfaceTransformation(transformationSpec)
                            ) {
                                Text(
                                    text = "${counter.count}",
                                    style = MaterialTheme.typography.displayLarge,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        item(key = "row_${counter.id}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .transformedHeight(this, transformationSpec),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        WearCounterSync.sendActionFromWatch(
                                            context = context,
                                            action = WearCounterSync.ACTION_DECREMENT,
                                            counterId = counter.id
                                        )
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Remove,
                                        contentDescription = "Decrement ${counter.title}"
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        WearCounterSync.sendActionFromWatch(
                                            context = context,
                                            action = WearCounterSync.ACTION_INCREMENT,
                                            counterId = counter.id
                                        )
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Increment ${counter.title}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@WearPreviewDevices
@WearPreviewFontScales
@Composable
fun CounterWearAppPreview() {
    MaterialTheme {
        CounterWearApp()
    }
}
