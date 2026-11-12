Feature: Product search

  Background:
    Given I am logged in as "alice"

  Scenario: Sort search results by price
    When I search for "cable"
    And I sort the results by "Price: low to high"
    Then the first result is "USB-C Cable"

  @diagnostics
  Scenario: Browser console stays clean on the search page
    When I search for "cable"
    Then no errors are logged in the browser console

  @wip
  Scenario: Search suggestions
    When I type "lap" into the search box
    Then I see search suggestions
