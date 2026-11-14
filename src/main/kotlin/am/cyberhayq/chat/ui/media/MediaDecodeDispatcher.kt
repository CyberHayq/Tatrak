package am.cyberhayq.chat.ui.media

import kotlinx.coroutines.Dispatchers

internal val mediaDecodeDispatcher = Dispatchers.IO.limitedParallelism(2)
