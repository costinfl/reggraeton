@auth
Feature: Login
  As a registered customer
  I want to log in
  So that I can buy products

  Background:
    Given I am on the login page

  @smoke
  Scenario: Successful login
    When I log in as "alice" with password "secret123"
    Then I should see the welcome message "Welcome, alice"

  Scenario Outline: Rejected login
    When I log in as "<user>" with password "<password>"
    Then I should see the error "<error>"

    Examples: wrong credentials
      | user  | password  | error                        |
      | alice | wrong     | Invalid username or password |
      | carol | secret123 | Invalid username or password |

    @regression
    Examples: locked account
      | user | password  | error          |
      | bob  | secret123 | Account locked |
