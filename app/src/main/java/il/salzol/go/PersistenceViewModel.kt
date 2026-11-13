package il.salzol.go

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import il.salzol.core.db.PersistenceSummary
import il.salzol.core.db.SalzolDatabase
import il.salzol.core.db.SalzolRepository
import il.salzol.core.droid.Droid
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class PersistenceViewModel(app: Application) : AndroidViewModel(app) {
    private val repository: SalzolRepository =
        Droid.repository() ?: SalzolRepository(SalzolDatabase.getInstance(app))

    val summary: StateFlow<PersistenceSummary> = repository.summaryFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PersistenceSummary()
        )
}
