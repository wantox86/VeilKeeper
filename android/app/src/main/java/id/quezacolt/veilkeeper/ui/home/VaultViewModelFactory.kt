package id.quezacolt.veilkeeper.ui.home

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import id.quezacolt.veilkeeper.data.VaultRepository
import id.quezacolt.veilkeeper.ui.category.CategoryViewModel
import id.quezacolt.veilkeeper.ui.vault.AddItemViewModel
import id.quezacolt.veilkeeper.ui.vault.VaultDetailViewModel

/**
 * Builds per-screen [ViewModelProvider.Factory] instances for the Sprint 2
 * vault screens. Unlike [id.quezacolt.veilkeeper.ui.auth.AuthViewModelFactory]
 * (one factory instance shared by ViewModels with no extra constructor
 * args), these screens need a route-scoped ID (categoryId/itemId) baked
 * into the ViewModel's constructor, so each is built fresh at the call
 * site via the `viewModelFactory { initializer { ... } }` DSL
 * (androidx.lifecycle.viewmodel, no extra dependency needed).
 */
object VaultViewModelFactory {
    fun home(repository: VaultRepository): ViewModelProvider.Factory = viewModelFactory {
        initializer { HomeViewModel(repository) }
    }

    fun category(repository: VaultRepository, categoryId: Long): ViewModelProvider.Factory = viewModelFactory {
        initializer { CategoryViewModel(repository, categoryId) }
    }

    fun vaultDetail(repository: VaultRepository, itemId: Long): ViewModelProvider.Factory = viewModelFactory {
        initializer { VaultDetailViewModel(repository, itemId) }
    }

    fun addItem(repository: VaultRepository, categoryId: Long): ViewModelProvider.Factory = viewModelFactory {
        initializer { AddItemViewModel(repository, categoryId) }
    }
}
