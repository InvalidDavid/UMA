name: Source request
description: Suggest a new source for UMA
labels: [Source request]
body:

  - type: input
    id: source-name
    attributes:
      label: Source name
      description: The name of the manga/comic source you want added.
      placeholder: |
        Example: "Not Real Scans"
    validations:
      required: true

  - type: input
    id: source-link
    attributes:
      label: Source link
      description: The URL of the source website.
      placeholder: |
        Example: "https://notrealscans.org"
    validations:
      required: true

  - type: input
    id: source-language
    attributes:
      label: Source language
      description: What language does this source provide content in?
      placeholder: |
        Example: "English"
    validations:
      required: true

  - type: textarea
    id: source-description
    attributes:
      label: Describe the source
      description: A clear and concise description of what this source provides.
      placeholder: |
        Example: "This source provides manga in English with daily updates..."
    validations:
      required: true

  - type: textarea
    id: alternatives
    attributes:
      label: Describe alternatives you've considered
      description: A clear and concise description of any alternative sources or solutions you've considered.
      placeholder: |
        Example: "Other sources like..."

  - type: textarea
    id: other-details
    attributes:
      label: Other details
      placeholder: |
        Additional details and attachments.
        Example:
          "18+/NSFW = yes"

  - type: checkboxes
    id: acknowledgements
    attributes:
      label: Acknowledgements
      description: Your issue will be closed if you haven't done these steps.
      options:
        - label: I have checked that the source does not already exist by searching via the search bar and verified it does not appear there.
          required: true
        - label: I have searched [existing issues](https://github.com/InvalidDavid/UMA/issues) both open & closed, and confirm that this is a new unreported issue.
          required: true
        - label: I have written a meaningful title with the source name.
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
