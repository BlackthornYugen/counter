package dev.jskw.counter.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import kotlinx.coroutines.delay

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
        if (counters.isEmpty()) {
            ScreenScaffold { contentPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        onClick = {
                            WearCounterSync.sendActionFromWatch(
                                context = context,
                                action = WearCounterSync.ACTION_CREATE,
                                title = "default watch counter"
                            )
                        },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        Text(
                            text = "No counters on phone\n\n+ Create Counter",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            val pagerState = rememberPagerState(pageCount = { counters.size })

            HorizontalPagerScaffold(
                pagerState = pagerState,
                pageIndicator = {
                    HorizontalPageIndicator(pagerState = pagerState)
                }
            ) {
                HorizontalPager(
                    state = pagerState
                ) { pageIndex ->
                    AnimatedPage(
                        pageIndex = pageIndex,
                        pagerState = pagerState
                    ) {
                        if (pageIndex in counters.indices) {
                            CounterPage(counter = counters[pageIndex])
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CounterPage(counter: WearCounter) {
    val context = LocalContext.current
    var showTitle by remember(counter.id) { mutableStateOf(true) }

    LaunchedEffect(counter.id) {
        showTitle = true
        delay(2000)
        showTitle = false
    }

    val titleAlpha by animateFloatAsState(
        targetValue = if (showTitle) 0.7f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "titleAlpha"
    )

    ScreenScaffold { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center
        ) {
            Card(
                onClick = {
                    showTitle = true
                    WearCounterSync.sendActionFromWatch(
                        context = context,
                        action = WearCounterSync.ACTION_INCREMENT,
                        counterId = counter.id
                    )
                },
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.65f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (titleAlpha > 0f) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 6.dp)
                                .graphicsLayer { alpha = titleAlpha },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (counter.isPublished) {
                                Icon(
                                    imageVector = Icons.Default.Cloud,
                                    contentDescription = "Synced",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = titleAlpha)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                            }
                            Text(
                                text = counter.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = titleAlpha),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Text(
                        text = "${counter.count}",
                        style = MaterialTheme.typography.displayLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.85f)
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        showTitle = true
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
                        showTitle = true
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

@WearPreviewDevices
@WearPreviewFontScales
@Composable
fun CounterWearAppPreview() {
    MaterialTheme {
        CounterWearApp()
    }
}
