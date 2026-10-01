package app.locomate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.R
import app.locomate.ui.theme.Inter
import app.locomate.ui.theme.LM

/** Native input with the approved Doop field tint, rim, spacing and Search vector. */
@Composable
internal fun SearchField(value: String, onValue: (String) -> Unit, loading: Boolean, onSubmit: () -> Unit) {
    BasicTextField(value, onValue,
        singleLine = true,
        textStyle = TextStyle(color = LM.Ink, fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(LM.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(18.dp))
            .semantics { contentDescription = "Train name or number" },
        decorationBox = { input ->
            Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.navigation_search), null, tint = LM.Ink3, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Retain the visible label after typing, without a floating outline cutout.
                    if (value.isNotEmpty()) Text("Train name or number", color = LM.Ink3, fontSize = 12.5.sp)
                    Box(Modifier.fillMaxWidth().heightIn(min = 22.dp), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text("Train name or number", color = LM.Ink3, fontSize = 16.sp)
                        input()
                    }
                }
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = LM.Accent, strokeWidth = 2.dp)
                else if (value.isNotEmpty()) IconButton(onClick = { onValue("") }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Cancel, contentDescription = "Clear search", tint = LM.Ink3, modifier = Modifier.size(20.dp))
                }
            }
        })
}
