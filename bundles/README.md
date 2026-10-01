# Published bundles

## patches-1.30.1-verifyfix.mpp — CURRENT

```
size    2036159
sha256  CF528A1573245B3A2CDB56BE361E1B0FEDFC9A7F2FE212B3FEB0997CAB44FBEA
```

Fixes the RevenueCat `VerifyError` that crashes any app reaching
`BillingFactory.createBilling`:

```
VerifyError: BillingWrapper.onPurchasesUpdated failed to verify:
[0x0] copyRes1 v9 <- result0 type=Undefined
```

`addInstructions` dropped the `invoke-static` to
`InAppRuntimePolicy.productIdFrom` when its argument register landed at
v16 or higher, stranding the following `move-result-object`. The affected
fake is disabled rather than left emitting broken bytecode, so this bundle
trades one RevenueCat feature for not crashing.

Also includes:
- `Unlock Premium` as an independent patch (not applied by default)
- a fix for `Unlock Premium` falsely forcing
  `FocusRingDrawable.isProjected()` in Material Components, via
  camelCase word-boundary matching and a framework-package skip list

Verified: Rizz (`com.rizzlabs.rizz`) launches and stays up with zero
`VerifyError`, zero `FATAL EXCEPTION`, zero `ITEM_UNAVAILABLE`.

Known limitation: RevenueCat-backed paywalls still show empty offerings.
Offerings come from RevenueCat's backend, which this bundle does not
intercept, so Rizz is crash-free but not unlocked.

## patches-1.29.0-dev.41-il2cppfix.mpp — SUPERSEDED, CONTAINS A CRASH BUG

```
size    2356034
sha256  B4F64402DC0FF08DF979178B51F02A292747665950EE90AA37C9796303B5EC0F
```

Kept because it is the Cut the Rope 2 verified build: subscription and
`royalbundle` complete, coins `80 -> 50080`, Superior Bundle inventory
doubles.

**Do not use on RevenueCat apps.** It carries the `VerifyError` described
above and will crash them on first purchase. Use
`patches-1.30.1-verifyfix.mpp` instead unless you specifically need the
CT2 behaviour, which was not re-verified on 1.30.1.
