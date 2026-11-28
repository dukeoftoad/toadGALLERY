import 'package:flutter/cupertino.dart';
import 'package:toadgallery/core/configuration.dart';
import "package:toadgallery/service_locator.dart";
import "package:toadgallery/ui/payment/store_subscription_page.dart";
import 'package:toadgallery/ui/payment/stripe_subscription_page.dart';

StatefulWidget getSubscriptionPage({bool isOnBoarding = false}) {
  if (updateService.isIndependentFlavor()) {
    return StripeSubscriptionPage(isOnboarding: isOnBoarding);
  }
  if (flagService.enableStripe && _isUserCreatedPostStripeSupport()) {
    return StripeSubscriptionPage(isOnboarding: isOnBoarding);
  } else {
    return StoreSubscriptionPage(isOnboarding: isOnBoarding);
  }
}

// Older users may have an active Play/App Store subscription.
bool _isUserCreatedPostStripeSupport() {
  return Configuration.instance.getUserID()! > 1580559962386460;
}
