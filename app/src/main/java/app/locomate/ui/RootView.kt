package app.locomate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.locomate.ui.theme.LocomateTheme

enum class Tab { Journeys, Explore, Passport }

@Composable
fun RootView() {
    var tab by remember { mutableStateOf(Tab.Journeys) }
    var searchOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        when (tab) {
            Tab.Journeys -> JourneyScreen()
            Tab.Explore  -> ExploreScreen()
            Tab.Passport -> PassportScreen()
        }
        // Capsule navbar floats over the map
        CapsuleNavBar(
            tab = tab,
            onTab = { tab = it },
            onSearch = { searchOpen = true },
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 22.dp)
        )
    }
    if (searchOpen) SearchSheet(onClose = { searchOpen = false })
}

@Composable
fun JourneyScreen() {
    Text("Journey — My Journeys / 1 day 4 hours until departure", modifier = Modifier.padding(40.dp))
}
@Composable fun ExploreScreen() { Text("Explore — network stats", modifier = Modifier.padding(40.dp)) }
@Composable fun PassportScreen() { Text("Passport — 8,412 km hero", modifier = Modifier.padding(40.dp)) }
@Composable fun CapsuleNavBar(tab: Tab, onTab: (Tab) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface) { Text("Navbar: ${tab.name}") }
}
@Composable fun SearchSheet(onClose: () -> Unit) {}
