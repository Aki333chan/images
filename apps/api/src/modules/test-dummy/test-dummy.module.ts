import { Module } from '@nestjs/common';
import { TestDummyController } from './test-dummy.controller';

@Module({
  controllers: [TestDummyController],
})
export class TestDummyModule {}
