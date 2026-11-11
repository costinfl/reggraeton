@cart
Feature: Shopping cart

  Background:
    Given I am logged in as "alice"

  Rule: Adding products

    @smoke
    Scenario: Add a single product
      When I search for "laptop"
      And I add "Laptop Pro 14" to the cart
      Then the cart badge shows 1 item

    Scenario: Add several products
      When I add the following products to the cart:
        | product       | quantity |
        | Laptop Pro 14 | 1        |
        | USB-C Cable   | 3        |
      Then the cart contains 2 lines
      And the cart total is 1329.97 EUR

  Rule: Checkout

    Scenario: Checkout with a delivery note
      Given the cart contains "USB-C Cable"
      When I check out with the note:
        """
        Please leave the parcel at the door.
        """
      Then I see the order confirmation
