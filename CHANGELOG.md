# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

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

[Unreleased]: https://github.com/vvvvalvalval/datomock/compare/v0.1.0...HEAD
