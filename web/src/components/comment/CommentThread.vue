<script lang="ts" setup>
import { Locator } from '@/data';
import { CommentRepository } from '@/data/api';
import { Comment1 } from '@/model/Comment';
import { runCatching } from '@/util/result';
import { doAction, copyToClipBoard } from '@/pages//util';

const { site, comment } = defineProps<{
  site: string;
  comment: Comment1;
  locked: boolean;
}>();

const message = useMessage();

const currentPage = ref(1);
const pageCount = ref(Math.floor((comment.numReplies + 9) / 10));

const draftRepo = Locator.draftRepository();
const draftId = `comment-${site}`;

const loadReplies = async (page: number) => {
  const result = await runCatching(
    CommentRepository.listComment({
      site,
      parentId: comment.id,
      page: page - 1,
      pageSize: 10,
    }),
  );
  if (result.ok) {
    pageCount.value = result.value.pageNumber;
    comment.replies = result.value.items;
  } else {
    message.error('回复加载错误：' + result.error.message);
  }
};

watch(currentPage, async (page) => loadReplies(page));

function onReplied() {
  showInput.value = false;
  if (currentPage >= pageCount) {
    loadReplies(currentPage.value);
  }
  draftRepo.addDraft.cancel();
  draftRepo.removeDraft(draftId);
}

const hideComment = (comment: Comment1) =>
  doAction(
    CommentRepository.hideComment(comment.id).then(
      () => (comment.hidden = true),
    ),
    '隐藏',
    message,
  );

const unhideComment = (comment: Comment1) =>
  doAction(
    CommentRepository.unhideComment(comment.id).then(
      () => (comment.hidden = false),
    ),
    '解除隐藏',
    message,
  );

const copyComment = (comment: Comment1) =>
  copyToClipBoard(comment.content).then((isSuccess) => {
    if (isSuccess) message.success('复制成功');
    else message.error('复制失败');
  });

const showInput = ref(false);
</script>

<template>
  <div ref="topElement" />
  <CommentItem
    :comment="comment"
    top-level
    @hide="hideComment"
    @unhide="unhideComment"
    @copy="copyComment"
    @reply="showInput = !showInput"
  />

  <CommentEditor
    v-if="showInput"
    :site="site"
    :draft-id="draftId"
    :parent="comment.id"
    :placeholder="`回复${comment.user.username}`"
    style="padding-top: 8px"
    @replied="onReplied()"
    @cancel="showInput = false"
  />

  <div
    v-for="replyComment in comment.replies"
    style="margin-left: 30px; margin-top: 20px"
  >
    <CommentItem
      :comment="replyComment"
      @hide="hideComment"
      @unhide="unhideComment"
      @copy="copyComment"
    />
  </div>

  <n-pagination
    v-if="comment.numReplies > 10"
    v-model:page="currentPage"
    :page-count="pageCount"
    :page-slot="7"
    style="margin-left: 30px; margin-top: 20px"
  />
</template>
