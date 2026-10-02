package com.telefam.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.chat.PollData
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.poll_votes
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

@Composable
fun PollBubble(
    poll: PollData,
    currentUserId: String,
    onVote: (selectedOptionIndexes: List<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    val voted = poll.hasVoted(currentUserId)
    val myVotes = poll.votes.filterValues { currentUserId in it }.keys

    Column(
        modifier.width(280.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Poll, contentDescription = null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(poll.question, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(14.dp))

        poll.options.forEachIndexed { index, optionText ->
            PollOptionRow(
                text = optionText,
                percent = poll.percentFor(index),
                selected = index in myVotes,
                showResults = voted,
                onClick = {
                    val newSelection = if (poll.allowMultiple) {
                        if (index in myVotes) myVotes - index else myVotes + index
                    } else {
                        setOf(index)
                    }
                    onVote(newSelection.toList())
                }
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(Res.string.poll_votes, poll.totalVoters().toString()),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun PollOptionRow(text: String, percent: Int, selected: Boolean, showResults: Boolean, onClick: () -> Unit) {
    val animatedPercent by animateFloatAsState(targetValue = if (showResults) percent / 100f else 0f, animationSpec = tween(400))

    Box(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        if (showResults) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(fraction = animatedPercent.coerceIn(0f, 1f))
                    .background(TelefamColors.PrimaryRed.copy(alpha = 0.18f))
            )
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            if (showResults) {
                Text("$percent%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}
