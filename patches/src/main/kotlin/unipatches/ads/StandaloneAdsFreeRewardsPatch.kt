package unipatches.ads

import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import java.util.logging.Logger

/**
 * Standalone entry for the historical Ads Free Rewards implementation. The adapters and
 * fingerprints are shared with Control App Ads so both entries receive the same fixes.
 *
 * Historical source: Zanuaimi/UniPatches, before 3b3030d consolidated the two patches.
 * Original reward work is credited to Nai64Patches.
 */
@Suppress("unused")
val standaloneAdsFreeRewardsPatch = bytecodePatch(
    name = "Ads Free Rewards (Standalone, Experimental)",
    description = """
        Grant supported rewarded-ad rewards without watching the ad. This restores a separate
        Ads Free Rewards entry using the historical UniPatches reward adapters.

        Select this patch by itself. Do not select Control App Ads in the same operation: both
        entries use the same reward methods, so applying both can produce duplicate hooks.
        Unsupported SDK paths and rewards verified by a server remain unchanged.

        Credits: Nai64Patches from Nai64; historical integration by Zanuaimi.
    """.trimIndent(),
    default = false,
) {
    val skipRewardedAds by booleanOption(
        title = "Skip rewarded ads",
        key = "standaloneRewardsSkip",
        default = true,
    )
    val instantReward by booleanOption(
        title = "Instant reward",
        key = "standaloneRewardsInstant",
        default = true,
    )
    val fakeAdAvailability by booleanOption(
        title = "Fake ad availability",
        key = "standaloneRewardsAvailability",
        default = true,
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val settings = AdsPatchSettings(
            noAdsEnabled = false,
            blockInterstitials = false,
            blockBanners = false,
            blockAppOpen = false,
            blockMRec = false,
            blockRewarded = false,
            blockNative = false,
            rewardsEnabled = true,
            skipRewardedAds = skipRewardedAds == true,
            instantReward = instantReward == true,
            fakeAdAvailability = fakeAdAvailability == true,
            hostsEnabled = false,
            wildcardHosts = false,
        )
        val plan = AdsPatchPlanner.resolve(
            settings = settings,
            selection = AdsRuntimeSelection(
                policyEnabled = false,
                noAdsModuleSelected = false,
                rewardsModuleSelected = false,
                hostsModuleSelected = false,
            ),
            sdkCoverage = AdsSdkCoverage(),
        )
        if (plan.rewards.mode == AdsPatchMode.STATIC) {
            applyAdsFreeRewards(logger, settings, plan)
        } else {
            logger.warning("Ads Free Rewards: no reward behavior selected - no changes applied")
        }
    }
}
