import "package:flutter/widgets.dart";
import "package:toadgallery/ui/payment/stripe_subscription_page.dart";

StatefulWidget getSubscriptionPage({bool isOnBoarding = false}) {
  return StripeSubscriptionPage(isOnboarding: isOnBoarding);
}
