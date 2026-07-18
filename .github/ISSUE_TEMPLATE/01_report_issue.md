---
name: 🐞 Bug Report
description: Create a report to help us improve
labels: [bug]
---

body:
  - type: markdown
    attributes:
      value: |
        Thanks for taking the time to fill out this bug report!

  - type: textarea
    id: bug-description
    attributes:
      label: Describe the bug
      description: A clear and concise description of what the bug is.
      placeholder: |
        Describe what happened...
    validations:
      required: true

  - type: textarea
    id: reproduce-steps
    attributes:
      label: Steps to reproduce
      description: How do you trigger this bug? Please walk us through it step by step.
      value: |
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
        Describe what should happen instead...
    validations:
      required: true

  - type: textarea
    id: screenshots
    attributes:
      label: Screenshots
      description: If applicable, add screenshots to help explain your problem.
      placeholder: |
        Paste screenshots here...

  - type: dropdown
    id: device
    attributes:
      label: Device
      description: What device are you using?
      placeholder: Select a device...
      options:
        - Samsung Galaxy S21
        - Samsung Galaxy S22
        - Samsung Galaxy S23
        - Samsung Galaxy S24
        - Google Pixel
        - Other Android Device
    validations:
      required: true

  - type: input
    id: os-version
    attributes:
      label: OS Version
      description: What Android version are you running?
      placeholder: "e.g. Android 12"
    validations:
      required: true

  - type: input
    id: app-version
    attributes:
      label: App Version
      description: What version of the app are you using?
      placeholder: "e.g. 1.0.0"
    validations:
      required: true

  - type: textarea
    id: additional-context
    attributes:
      label: Additional context
      description: Add any other context about the problem here.
      placeholder: |
        Add any other relevant information...

  - type: checkboxes
    id: acknowledgements
    attributes:
      label: Acknowledgements
      description: Please confirm the following before submitting
      options:
        - label: I have searched [existing issues](https://github.com/InvalidDavid/UMA/issues) both open & closed, and confirm that this is a new unreported issue.
          required: true
        - label: I have written a short but informative title.
          required: true
        - label: I have tried the latest version of the app.
          required: true
        - label: I will correctly fill out all of the requested information in this form.
          required: true
