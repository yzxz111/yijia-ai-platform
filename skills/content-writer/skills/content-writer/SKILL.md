---
name: content-writer
description: '依据已确认的选题简报，为小红书、知乎、微信公众号与短视频渠道撰写可评审的原创中文社交内容草稿。'
metadata:
  title.zh: '多平台内容创作'
  title.en: 'Multi-Platform Content Creation'
  summary.en: 'Create review-ready, original Chinese social-media drafts for Xiaohongshu, Zhihu, WeChat Official Accounts, and short-video channels from an approved brief.'
---
# Multi-Platform Content Creation

## Purpose

Create platform-aware social-media drafts from an approved topic, evidence, audience, and brand brief. This Skill includes WeChat Official Account structure and search-keyword planning as part of the multi-platform writing capability.

## When to use

Use after the user has chosen a topic or strategy item and needs a draft for Xiaohongshu, Zhihu, WeChat Official Accounts, Douyin, Bilibili, or another specified channel.

Do not use it to publish content, write undisclosed endorsements, fabricate first-person experience, or imitate a living writer's style.

## Inputs

- Required: topic, target platform, audience, intended action, and brand or campaign context.
- Required: supported facts, approved claims, or a statement that the content is opinion or fiction.
- Optional: desired length, tone, content pillar, keywords, visual brief, call to action, product restrictions, and source trend brief.

## Procedure

1. Confirm the platform, audience, goal, claim boundaries, and whether the draft needs facts, citations, visuals, or a script.
2. Select a platform-native structure. Adapt the hook, pacing, headings, titles, tags, and calls to action rather than reposting identical copy.
3. Create original text. Use supplied evidence for factual or comparative statements and mark unsupported claims for review.
4. For WeChat Official Accounts, prepare title options, article structure, keyword placement, summary, cover-image brief, and a publication-ready Markdown draft; this remains a draft.
5. For video channels, include scene or shot guidance, spoken copy, duration, caption, and on-screen-text notes where requested.
6. Deliver variants and an editorial review list, including all statements that require legal, factual, brand, or rights approval.

## Output contract

Return a result containing:

```json
{
  "taskStatus": "completed | partial | needs_input | blocked",
  "drafts": [
    {
      "platform": "string",
      "format": "post | article | answer | video_script",
      "titleOptions": ["string"],
      "body": "string",
      "tagsOrKeywords": ["string"],
      "visualBrief": ["string"],
      "callToAction": "string"
    }
  ],
  "evidence": [{ "claim": "string", "source": "string | user-approved" }],
  "reviewRequired": ["string"],
  "missingInfo": ["string"]
}
```

## Safety and boundaries

External writes: none.

- Produce drafts only; do not submit to a draft box, schedule, or publish on any platform.
- Do not invent personal use, customer reviews, results, data, credentials, quotations, or regulatory approval.
- Do not copy protected post text or present another creator's work as original. Keep any necessary quotations short and attributed.
- Flag regulated, medical, financial, legal, price, comparative, promotional, and personal-data claims for human review.
- Avoid deceptive urgency, prohibited content, discriminatory language, and instructions intended to evade platform rules.

## Quality checklist

- The draft matches the intended platform, audience, and action.
- Material claims map to approved evidence or are marked for review.
- Titles, hooks, calls to action, and keywords are relevant rather than clickbait or misleading.
- The output is clearly labelled as a review-ready draft, not a published result.
