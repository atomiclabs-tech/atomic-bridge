import pytest

from atomic_relay.notifications.filter import PackageFilter


@pytest.mark.parametrize("package", ["com.example", "com.example.child", "other", "", "UPPER_123.name"])
def test_empty_lists_keep_every_package(package):
    assert PackageFilter().keeps(package)


@pytest.mark.parametrize("package, expected", [
    ("com.example", True), ("com.example.child", False), ("com.examples", False),
    ("org.allowed.child", True), ("org.allowed.deep.child", True), ("org.allowed", False),
    ("org.allowedness.child", False), ("COM.EXAMPLE", False),
])
def test_exact_and_prefix_allow_matches_are_case_sensitive_and_dot_bounded(package, expected):
    assert PackageFilter(("com.example", "org.allowed.*"), ()).keeps(package) is expected


@pytest.mark.parametrize("package, expected", [
    ("com.example.bank", False), ("com.example.bank.child", True), ("com.example.private.child", False),
    ("com.example.other", True), ("org.other", False),
])
def test_deny_takes_precedence_over_nonempty_allow(package, expected):
    policy = PackageFilter(("com.example.*", "com.example.bank"), ("com.example.bank", "com.example.private.*"))
    assert policy.keeps(package) is expected


def test_deny_only_keeps_unmatched_packages():
    policy = PackageFilter((), ("com.example.bank", "private.*"))
    assert not policy.keeps("com.example.bank") and not policy.keeps("private.child")
    assert policy.keeps("com.example.other")


@pytest.mark.parametrize("pattern", ["", "*", ".*", "com.*.bank", "com.example*", "com/example", "com.example-1",
                                   "com.example?", "com example", "com.é", " com.example", "com.example\n", None])
@pytest.mark.parametrize("side", ["allow", "deny"])
def test_invalid_patterns_raise_without_input_text(pattern, side):
    with pytest.raises(ValueError, match="invalid notification package pattern"):
        PackageFilter(**{side: (pattern,)})


@pytest.mark.parametrize("package", [None, 123, {}, []])
def test_malformed_event_package_fails_instead_of_being_silently_filtered(package):
    with pytest.raises(ValueError, match="notification package must be a string"):
        PackageFilter(("com.example",), ()).keeps(package)
