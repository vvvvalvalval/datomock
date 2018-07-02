# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Added
- Testing with test.check
### Fixed
- Fixed mock Log implementation so that txRange responds well to nil, Dates or transaction entids as bounds
### Removed
- Tests for Clojure 1.6 - incompatible with test.check

### 0.2.1 - 2018-06-15
### Added
- tests for datomic.api/settable-future.
### Fixed
- datomic.api/transact no longer throws when the transaction fails.

## 0.2.0 - 2017-03-01
### Added
- Log API support
### Changed
- dependencies versions for tests

## 0.1.0 - 2016-02-14
### Added
- mock-conn fn (atom-based implementation of datomic.Connection)
- fork-conn fn
- empty-db fn
- tests

[Unreleased]: https://github.com/vvvvalvalval/datomock/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/vvvvalvalval/datomock/compare/v0.2.0...0.2.1
[0.2.0]: https://github.com/vvvvalvalval/datomock/compare/v0.1.0...0.2.0
