package one.mixin.android.di.module

import dagger.Module
import dagger.android.ContributesAndroidInjector
import one.mixin.android.ui.group.InviteFragment

@Module
abstract class InviteActivityModule {
    @ContributesAndroidInjector
    internal abstract fun contributeForwardFragment(): InviteFragment
}
