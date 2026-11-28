import "dart:async";

import "package:flutter/material.dart";
import "package:toadgallery/core/event_bus.dart";
import "package:toadgallery/events/contacts_changed_event.dart";
import "package:toadgallery/events/people_changed_event.dart";
import "package:toadgallery/models/api/collection/user.dart";
import "package:toadgallery/services/contacts/contact_identity_resolver.dart";

typedef ResolvedSocialUserNameBuilder =
    Widget Function(BuildContext context, String resolvedName);

class ResolvedSocialUserName extends StatefulWidget {
  final User user;
  final ResolvedSocialUserNameBuilder builder;

  const ResolvedSocialUserName({
    required this.user,
    required this.builder,
    super.key,
  });

  @override
  State<ResolvedSocialUserName> createState() => _ResolvedSocialUserNameState();
}

class _ResolvedSocialUserNameState extends State<ResolvedSocialUserName> {
  StreamSubscription<ContactsChangedEvent>? _contactsChangedSubscription;
  StreamSubscription<PeopleChangedEvent>? _peopleChangedSubscription;

  @override
  void initState() {
    super.initState();
    _contactsChangedSubscription = Bus.instance
        .on<ContactsChangedEvent>()
        .listen((event) {
          if (mounted && event.matchesContactUserId(widget.user.id)) {
            setState(() {});
          }
        });
    _peopleChangedSubscription = Bus.instance.on<PeopleChangedEvent>().listen((
      _,
    ) {
      if (mounted) {
        setState(() {});
      }
    });
  }

  @override
  void dispose() {
    _contactsChangedSubscription?.cancel();
    _peopleChangedSubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return widget.builder(context, resolveDisplayName(widget.user));
  }
}
