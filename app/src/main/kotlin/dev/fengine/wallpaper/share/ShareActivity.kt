package dev.fengine.wallpaper.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.MainActivity
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.ui.FengineTheme
import dev.fengine.wallpaper.ui.design.BodyMuted
import dev.fengine.wallpaper.ui.design.Caption
import dev.fengine.wallpaper.ui.design.ChoiceRow
import dev.fengine.wallpaper.ui.design.Headline
import dev.fengine.wallpaper.ui.design.Page
import dev.fengine.wallpaper.ui.design.Section
import dev.fengine.wallpaper.ui.design.Space
import dev.fengine.wallpaper.ui.design.TextAction

/**
 * "Share › Feels Engine" from Google Photos (or any app sharing images): there is no 100-photo
 * limit, and a search in Google Photos (a person, "beach") selects across the whole library, cloud
 * included. The grant ends when this screen closes, so saving starts at once ([ShareViewModel]).
 */
class ShareActivity : ComponentActivity() {
    private val vm: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.start(sharedUris(intent))
        val store = (application as FengineApp).settings
        setContent {
            val settings by store.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings ?: return@setContent
            FengineTheme(s.themeMode, s.colorSource) {
                ShareScreen(
                    vm,
                    s,
                    onDone = { name, findIn ->
                        if (findIn != null)
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_FIND_IN, findIn)
                                    .addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    )
                            )
                        else Toast.makeText(this, "Added to $name", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onCancel = {
                        vm.cancel()
                        finish()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) vm.closing()
        super.onDestroy()
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        val many =
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val one = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val clip = intent.clipData?.let { c -> List(c.itemCount) { c.getItemAt(it).uri } }
        return (many.orEmpty() + listOfNotNull(one) + clip.orEmpty().filterNotNull()).distinct()
    }
}

@Composable
private fun ShareScreen(
    vm: ShareViewModel,
    settings: Settings,
    onDone: (name: String, findIn: String?) -> Unit,
    onCancel: () -> Unit,
) {
    val st by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(st.addedTo) { st.addedTo?.let { onDone(it, st.findIn) } }
    val groups = settings.groups
    // The group on the home screen first; a new group when there are none.
    val first = groups.firstOrNull { it.id == settings.homeGroupId } ?: groups.firstOrNull()
    var groupId by rememberSaveable { mutableStateOf(first?.id) }
    val group = groups.firstOrNull { it.id == groupId }
    var lookThrough by rememberSaveable { mutableStateOf(true) }
    val canLook = group?.hasDescription == true
    val chosen = st.choice != null

    Page {
        Headline(
            if (st.total == 1) "1 photo" else "${st.total} photos",
            caption =
                when {
                    st.saving && st.failed > 0 && st.saved.isNotEmpty() ->
                        "Saved ${st.saved.size}. Waiting for Google Photos to download ${st.failed}."
                    st.saving -> "Saving a copy of each: ${st.saved.size} of ${st.total}."
                    st.failed > 0 -> "Saved ${st.saved.size}. ${st.failed} could not be read."
                    else -> "Saved ${st.saved.size}."
                },
        )
        Spacer(Modifier.height(Space.md))
        if (st.saving)
            LinearProgressIndicator(
                progress = { if (st.total == 0) 0f else st.saved.size.toFloat() / st.total },
                modifier = Modifier.fillMaxWidth(),
            )
        Spacer(Modifier.height(Space.xl))
        Section("Add to") {
            groups.forEach { g ->
                ChoiceRow(
                    label = g.name,
                    description =
                        if (g.hasDescription) "Finds: ${g.rule}" else "${g.allPhotos.size} photos",
                    selected = g.id == groupId,
                    onClick = { if (!chosen) groupId = g.id },
                )
            }
            ChoiceRow(
                label = "New group",
                description = "Named ${ShareViewModel.NEW_GROUP}; rename it in the app.",
                selected = groupId == null,
                onClick = { if (!chosen) groupId = null },
            )
        }
        if (canLook)
            Section("Which ones") {
                ChoiceRow(
                    label = "Only the ones that show it",
                    description =
                        "The phone looks at each for “${group!!.rule}” and keeps one photo per moment.",
                    selected = lookThrough,
                    onClick = { if (!chosen) lookThrough = true },
                )
                ChoiceRow(
                    label = "All of them",
                    description = "Every photo joins the group as it is.",
                    selected = !lookThrough,
                    onClick = { if (!chosen) lookThrough = false },
                )
            }
        val none = !st.saving && st.saved.isEmpty()
        Button(
            onClick = { vm.choose(groupId, canLook && lookThrough) },
            enabled = !chosen && !none,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (chosen) "Adding when saved…" else "Add")
        }
        Spacer(Modifier.height(Space.sm))
        when {
            none -> BodyMuted("None of these could be read. Try sharing them again in a minute.")
            chosen ->
                Caption(
                    "Keep this open until saving is done: the photos can only be read while it is."
                )
            else ->
                Caption(
                    "Saving goes on while you choose. The copies stay in the app, screen-sized."
                )
        }
        TextAction("Cancel", onCancel)
    }
}
