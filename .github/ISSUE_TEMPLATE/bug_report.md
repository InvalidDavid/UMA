name: Bug report
description: Create a report to help us improve
labels: [Bug]
body:

  - type: textarea
    id: describe-bug
    attributes:
      label: Describe the bug
      description: A clear and concise description of what the bug is.
      placeholder: |
        Example: "The app crashes when..."
    validations:
      required: true

  - type: textarea
    id: reproduce-steps
    attributes:
      label: Steps to reproduce
      description: Provide steps to reproduce the issue.
      placeholder: |
        Example:
          1. Go to '...'
          2. Click on '....'
          3. Scroll down to '....'
          4. See error
    validations:
      required: true

  - type: textarea
    id: expected-behavior
    attributes:
      label: Expected behavior
      description: A clear and concise description of what you expected to happen.
      placeholder: |
        Example: "The app should display the content without crashing"
    validations:
      required: true

  - type: textarea
    id: actual-behavior
    attributes:
      label: Actual behavior
      description: What actually happened instead.
      placeholder: |
        Example: "The app crashes with an error"
    validations:
      required: true

  - type: textarea
    id: screenshots
    attributes:
      label: Screenshots
      description: If applicable, add screenshots to help explain your problem.
      placeholder: |
        Attach screenshots or screen recordings here

  - type: input
    id: app-version
    attributes:
      label: App version
      description: You can find the app version in settings or about page.
      placeholder: |
        Example: "1.0.0"
    validations:
      required: true

  - type: input
    id: android-version
    attributes:
      label: Android version
      description: You can find this in your Android settings.
      placeholder: |
        Example: "Android 12"
    validations:
      required: true

  - type: input
    id: device-info
    attributes:
      label: Device information
      description: What device are you using?
      placeholder: |
        Example: "Samsung Galaxy S21"
    validations:
      required: true

  - type: textarea
    id: other-details
    attributes:
      label: Additional context
      description: Add any other context about the problem here.
      placeholder: |
        Additional details, logs, or attachments.

  - type: checkboxes
    id: acknowledgements
    attributes:
      label: Acknowledgements
      description: Your issue will be closed if you haven't done these steps.
      options:
        - label: I have searched [existing issues](https://github.com/InvalidDavid/UMA/issues) both open & closed, and confirm that this is a new unreported issue.
          required: true
        - label: I have written a short but informative title.
          required: true
        - label: I have tried the latest version of the app.
          required: true
        - label: I will correctly fill out all of the requested information in this form.
          required: true

  - type: textarea
    attributes:
      label: <!-- footer -->
      description: Do **not** modify. This is a reminder for other users to vote.
      value: |
        ---

        Add a :+1: [reaction] to [issues you find important].

        [reaction]: https://github.blog/2016-03-10-add-reactions-to-pull-requests-issues-and-comments/
        [issues you find important]: https://github.com/InvalidDavid/UMA/issues?q=is%3Aissue+is%3Aopen+sort%3Areactions-%2B1-desc
