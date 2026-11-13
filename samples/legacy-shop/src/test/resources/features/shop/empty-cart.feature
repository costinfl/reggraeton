@cart
Feature: Emptying the cart

  Background:
    Given I am logged in as "alice"
    And the cart contains "USB-C Cable"

  Scenario: Confirm emptying the cart
    When I empty the cart and confirm
    Then the cart contains 0 lines

  Scenario: Cancel emptying the cart
    When I empty the cart but cancel
    Then the cart contains 1 lines
